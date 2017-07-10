/*
 * Copyright 2016-2018 Daniel Urban and contributors listed in AUTHORS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example

import cats.implicits._

final class Sm[S[_, _, _], F, T, A] private (
  private val repr: IxFree[S, F, T, A]
) {

  import Sm.Execute

  def flatMap[B, U](f: A => Sm[S, T, U, B]): Sm[S, F, U, B] =
    new Sm(repr.flatMap(a => f(a).repr))

  def map[B](f: A => B): Sm[S, F, T, B] =
    new Sm(repr.map(f))

  // TODO: infer `R`
  def run[M[_] : cats.Monad : scalaz.Monad, R[_]](
    implicit
    exec: Execute.Aux[S, M, R, F, T]
  ): M[A] = {
    val fx = new FunctionX[S, Sm.ResRepr[M, exec.Res]#λ] {
      def apply[G, U, X](sa: S[G, U, X]): scalaz.IndexedStateT[M, exec.Res[G], exec.Res[U], X] = {
        scalaz.IndexedStateT { res: exec.Res[G] =>
          exec.exec(res)(sa)
        }
      }
    }
    val st = repr.foldMap[Sm.ResRepr[M, exec.Res]#λ](fx)
    for {
      resource <- exec.init
      rr <- st.run(resource)
      (resource, result) = rr
      _ <- exec.fin(resource)
    } yield result
  }
}

object Sm {

  type ResRepr[M[_], Res[_]] = {
    type λ[f, t, x] = scalaz.IndexedStateT[M, Res[f], Res[t], x]
  }

  trait Create[S[_, _, _]] {
    type Res[st]
    type Init
    def mk: Res[Init]
  }

  object Create {

    type Aux[S[_, _, _], R[_], I] = Create[S] {
      type Res[st] = R[st]
      type Init = I
    }

    def apply[S[_, _, _]](implicit inst: Create[S]): Create.Aux[S, inst.Res, inst.Init] =
      inst

    def instance[S[_, _, _], R[_], I](create: => R[I]): Create.Aux[S, R, I] = new Create[S] {
      type Res[st] = R[st]
      type Init = I
      def mk: R[I] = create
    }
  }

  trait Execute[S[_, _, _]] {
    type M[a]
    type InitSt
    type FinSt
    type Res[st]
    def init: M[Res[InitSt]]
    def exec[F, T, A](res: Res[F])(sa: S[F, T, A]): M[(Res[T], A)]
    def fin(ref: Res[FinSt]): M[Unit]
  }

  object Execute {
    type Aux[S[_, _, _], M0[_], R[_], F, T] = Execute[S] {
      type M[a] = M0[a]
      type InitSt = F
      type FinSt = T
      type Res[st] = R[st]
    }
  }

  def pure[S[_, _, _], F, A](a: A): Sm[S, F, F, A] =
    new Sm(IxFree.pure(a))

  def liftF[S[_, _, _], F, T, A](sa: S[F, T, A]): Sm[S, F, T, A] =
    new Sm(IxFree.liftF(sa))
}
