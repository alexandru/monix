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

import monix.eval.Coeval
import monix.eval.Coeval.{Always, Eager, Error, FlatMap, Map, Now, Once, Suspend}
import monix.eval.Coeval.{NOW_ID, ERROR_ID, ALWAYS_ID, ONCE_ID, FRAME_ID, SUSPEND_ID}
import monix.execution.internal.collection.ArrayStack
import monix.execution.misc.NonFatal

private[eval] object CoevalRunLoop {
  private type Current = Coeval[Any]
  private type Bind = Coeval.Frame[Any, Any]
  private type CallStack = ArrayStack[Bind]

  /** Trampoline for lazy evaluation. */
  def start[A](source: Coeval[A]): Eager[A] = {
    var current: Current = source
    var bFirst: Bind = null
    var bRest: CallStack = null
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    do {
      current.id match {
        case FRAME_ID =>
          val ref = current.asInstanceOf[Bind]
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack[Bind]()
            bRest.push(bFirst)
          }
          bFirst = ref
          current = ref.source

        case NOW_ID =>
          val ref = current.asInstanceOf[Now[AnyRef]]
          unboxed = ref.value
          hasUnboxed = true

        case ALWAYS_ID =>
          val ref = current.asInstanceOf[Always[AnyRef]]
          // Indirection to avoid ObjectRef & BoxedUnit
          val value = try {
            val ret = ref.thunk()
            current = null
            hasUnboxed = true
            ret
          } catch {
            case e if NonFatal(e) =>
              current = new Error(e)
              null
          }
          unboxed = value

        case SUSPEND_ID =>
          val ref = current.asInstanceOf[Suspend[AnyRef]]
          // Indirection to avoid ObjectRef
          val next = try ref.thunk() catch { case e if NonFatal(e) => new Error(e) }
          current = next

        case ONCE_ID =>
          val ref = current.asInstanceOf[Once[AnyRef]]
          current = ref.run

        case ERROR_ID =>
          val ref = current.asInstanceOf[Error]
          val bind = findErrorHandler(bFirst, bRest)
          // Not pattern matching in order to avoid usage of "BoxedUnit"
          if (bind ne null) {
            bFirst = null
            // Indirection to avoid ObjectRef
            val next = try bind(ref.error) catch { case e if NonFatal(e) => new Error(e) }
            current = next
          } else {
            return ref
          }
      }

      if (hasUnboxed) {
        val bind = popNextBind(bFirst, bRest)
        // Not pattern matching in order to avoid usage of "BoxedUnit"
        if (bind ne null) {
          // Indirection to avoid ObjectRef
          val next = try bind(unboxed) catch { case e if NonFatal(e) => new Error(e) }
          current = next
          // No longer in unboxed state
          hasUnboxed = false
          bFirst = null
          unboxed = null // GC purposes
        } else {
          return (if (current ne null) current else Now(unboxed)).asInstanceOf[Eager[A]]
        }
      }
    } while (true)
    // $COVERAGE-OFF$
    null // Unreachable code
    // $COVERAGE-ON$
  }

  private def findErrorHandler(bFirst: Bind, bRest: CallStack): Throwable => Coeval[Any] = {
    var cursor = bFirst
    do {
      val next = cursor match {
        case FlatMap(_, _, g) if g ne null =>
          return g
        case _ =>
          if (bRest ne null) bRest.pop() else null
      }
      // Indirection to avoid ObjectRef and liftedTree$
      cursor = next
    } while (cursor ne null)
    // None found
    null
  }

  private def popNextBind(bFirst: Bind, bRest: CallStack): Any => Coeval[Any] = {
    var cursor = bFirst
    do {
      val next = cursor match {
        case FlatMap(_, f, _) if f ne null =>
          return f
        case ref @ Map(_, _, _) =>
          return ref
        case _ =>
          if (bRest ne null) bRest.pop() else null
      }
      // Indirection to avoid ObjectRef and liftedTree$
      cursor = next
    } while (cursor ne null)
    // None found
    null
  }
}
