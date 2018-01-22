/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
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

package monix.eval.internal

import monix.eval.Task
import monix.execution.misc.NonFatal

private[eval] object TaskBracket {
  /**
    * Implementation for `Task.bracket1`.
    */
  def simple[A, B](
    acquire: Task[A],
    use: A => Task[B],
    release: A => Task[Unit]): Task[B] = {

    either(acquire, use, (a, _) => release(a))

    acquire.flatMap { a =>
      val next = try use(a) catch { case NonFatal(e) => Task.raiseError(e) }
      next.onCancelRaiseError(isCancel).transformWith[B](
        b => release(a).map(_ => b),
        err => {
          if (err != isCancel)
            release(a).flatMap(_ => Task.raiseError[B](err))
          else
            release(a).flatMap(neverFn)
        }
      )
    }
  }

  /**
    * Implementation for `Task.bracket2`.
    */
  def either[A, B](
    acquire: Task[A],
    use: A => Task[B],
    release: (A, Either[Option[Throwable], B]) => Task[Unit]): Task[B] = {

    acquire.flatMap { a =>
      val next = try use(a) catch { case NonFatal(e) => Task.raiseError(e) }
      next.onCancelRaiseError(isCancel).transformWith[B](
        b => release(a, Right(b)).map(_ => b),
        err => {
          if (err != isCancel)
            release(a, Left(Some(err))).flatMap(_ => Task.raiseError[B](err))
          else
            release(a, leftNone).flatMap(neverFn)
        }
      )
    }
  }

  private final class CancelException extends RuntimeException
  private final val isCancel = new CancelException
  private final val neverFn = (_: Unit) => Task.never[Nothing]
  private final val leftNone = Left(None)
}
