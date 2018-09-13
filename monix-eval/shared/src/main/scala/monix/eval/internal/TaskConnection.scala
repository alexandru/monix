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

package monix.eval
package internal

import cats.effect.CancelToken
import monix.execution.Scheduler
import monix.execution.atomic.{Atomic, PaddingStrategy}
import monix.execution.internal.Platform

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

private[eval] sealed abstract class TaskConnection {
  /**
    * Cancels the unit of work represented by this reference.
    *
    * Guaranteed idempotency - calling it multiple times should have the
    * same side-effect as calling it only once. Implementations
    * of this method should also be thread-safe.
    */
  def cancel: CancelToken[Task]

  /**
    * @return true in case this cancelable hasn't been canceled,
    *         or false otherwise.
    */
  def isCanceled: Boolean

  /**
    * Pushes a cancelable reference on the stack, to be
    * popped or canceled later in FIFO order.
    */
  def push(token: CancelToken[Task]): Unit

  /**
    * Pushes a pair of `TaskConnection` on the stack, which on
    * cancellation will get trampolined.
    *
    * This is useful in `Task.race` for example, because combining
    * a whole collection of `Task` tasks, two by two, can lead to
    * building a cancelable that's stack unsafe.
    */
  def pushPair(lh: TaskConnection, rh: TaskConnection): Unit

  /**
    * Removes a cancelable reference from the stack in FIFO order.
    *
    * @return the cancelable reference that was removed.
    */
  def pop(): CancelToken[Task]

  /**
    * Tries to reset an `TaskConnection`, from a cancelled state,
    * back to a pristine state, but only if possible.
    *
    * Returns `true` on success, or `false` if there was a race
    * condition (i.e. the connection wasn't cancelled) or if
    * the type of the connection cannot be reactivated.
    */
  def tryReactivate(): Boolean
}

private[eval] object TaskConnection {
  /**
    * Builder for [[TaskConnection]].
    */
  def apply(s: Scheduler): TaskConnection =
    new Impl()(s)

  /**
    * Reusable [[TaskConnection]] reference that cannot
    * be canceled.
    */
  val uncancelable: TaskConnection =
    new Uncancelable

  private final class Uncancelable extends TaskConnection {
    def cancel: Task[Unit] = Task.unit
    def isCanceled: Boolean = false
    def push(token: CancelToken[Task]): Unit = ()
    def pop(): CancelToken[Task] = Task.unit
    def tryReactivate(): Boolean = true
    def pushPair(lh: TaskConnection, rh: TaskConnection): Unit = ()
  }

  private final class Impl(implicit s: Scheduler) extends TaskConnection {
    private[this] val state =
      Atomic.withPadding(List.empty[CancelToken[Task]], PaddingStrategy.LeftRight128)

    val cancel = Task.suspend {
      state.getAndSet(null) match {
        case null | Nil =>
          Task.unit
        case list =>
          cancelAll(list.iterator)
      }
    }

    def isCanceled: Boolean =
      state.get eq null

    @tailrec def push(cancelable: CancelToken[Task]): Unit =
      state.get match {
        case null =>
          cancelable.runAsync(Callback.empty)
        case list =>
          val update = cancelable :: list
          if (!state.compareAndSet(list, update)) push(cancelable)
      }

    def pushPair(lh: TaskConnection, rh: TaskConnection): Unit =
      push(cancelAll(lh.cancel, rh.cancel))

    @tailrec def pop(): CancelToken[Task] =
      state.get match {
        case null | Nil => Task.unit
        case current @ (x :: xs) =>
          if (!state.compareAndSet(current, xs)) pop()
          else x
      }

    def tryReactivate(): Boolean =
      state.compareAndSet(null, Nil)
  }

  /**
    * Given a list of cancel tokens, cancels all, delaying all
    * exceptions until all references are canceled.
    */
  def cancelAll(cancelables: CancelToken[Task]*): CancelToken[Task] = {
    if (cancelables.isEmpty) {
      Task.unit
    } else Task.suspend {
      cancelAll(cancelables.iterator)
    }
  }

  def cancelAll(cursor: Iterator[CancelToken[Task]]): CancelToken[Task] =
    if (cursor.isEmpty) {
      Task.unit
    } else Task.suspend {
      val frame = new CancelAllFrame(cursor)
      frame.loop()
    }

  // Optimization for `cancelAll`
  private final class CancelAllFrame(cursor: Iterator[CancelToken[Task]])
    extends StackFrame[Unit, Task[Unit]] {

    private[this] val errors = ListBuffer.empty[Throwable]

    def loop(): CancelToken[Task] = {
      if (cursor.hasNext) {
        cursor.next().flatMap(this)
      } else {
        errors.toList match {
          case Nil =>
            Task.unit
          case first :: rest =>
            Task.raiseError(Platform.composeErrors(first, rest: _*))
        }
      }
    }

    def apply(a: Unit): Task[Unit] =
      loop()

    def recover(e: Throwable): Task[Unit] = {
      errors += e
      loop()
    }
  }
}