/*
 * Copyright (c) 2014-2019 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
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

package monix.eval

import cats.Eval
import cats.effect._

import scala.annotation.implicitNotFound

/**
  * A lawless type class that specifies conversions from `Coeval`
  * to similar data types (i.e. pure, synchronous).
  */
@implicitNotFound("""Cannot find implicit value for CoevalLift[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait CoevalLift[F[_]] {
  /**
    * Converts `Coeval[A]` into `F[A]`.
    *
    * The operation should preserve referential transparency.
    */
  def coevalLift[A](coeval: Coeval[A]): F[A]
}

object CoevalLift extends CoevalLiftImplicits0 {
  /**
    * Returns the available [[CoevalLift]] instance for `F`.
    */
  def apply[F[_]](implicit F: CoevalLift[F]): CoevalLift[F] = F

  /**
    * Instance for converting to `Coeval`, being the identity function.
    */
  implicit val toCoeval: CoevalLift[Coeval] =
    new CoevalLift[Coeval] {
      def coevalLift[A](coeval: Coeval[A]): Coeval[A] = coeval
    }

  /**
    * Instance for converting to [[Task]].
    */
  implicit val toTask: CoevalLift[Task] =
    new CoevalLift[Task] {
      def coevalLift[A](coeval: Coeval[A]): Task[A] =
        Task.coeval(coeval)
    }

  /**
    * Instance for converting to `cats.Eval`.
    */
  implicit val toEval: CoevalLift[Eval] =
    new CoevalLift[Eval] {
      def coevalLift[A](coeval: Coeval[A]): Eval[A] =
        coeval match {
          case Coeval.Now(value) => Eval.now(value)
          case Coeval.Error(e) => Eval.always(throw e)
          case Coeval.Always(thunk) => new cats.Always(thunk)
          case other => Eval.always(other.value())
        }
    }
}

private[eval] abstract class CoevalLiftImplicits0 {
  /**
    * Instance for converting to any type implementing
    * [[https://typelevel.org/cats-effect/typeclasses/concurrent.html cats.effect.Concurrent]].
    */
  implicit def toSync[F[_]](implicit F: Sync[F]): CoevalLift[F] =
    new CoevalLift[F] {
      def coevalLift[A](coeval: Coeval[A]): F[A] =
        coeval match {
          case Coeval.Now(a) => F.pure(a)
          case Coeval.Error(e) => F.raiseError(e)
          case Coeval.Always(f) => F.delay(f())
          case _ => F.delay(coeval.value())
        }
    }
}