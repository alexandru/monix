/*
 * Copyright (c) 2014-2017 by The Monix Project Developers.
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
      current match {
        case ref @ FlatMap(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = createCallStack()
            bRest.push(bFirst)
          }
          bFirst = ref
          current = fa

        case Now(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Always(thunk) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            current = null
          } catch { case NonFatal(e) =>
            current = Error(e)
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = createCallStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          current = fa

        case Suspend(thunk) =>
          // Try/catch described as statement, otherwise ObjectRef happens ;-)
          try { current = thunk() }
          catch { case NonFatal(ex) => current = Error(ex) }

        case eval @ Once(_) =>
          current = eval.run

        case ref @ Error(error) =>
          tryRecoverError(error, bFirst, bRest) match {
            case null =>
              return ref
            case next =>
              current = next
              bFirst = null
          }
      }

      if (hasUnboxed) {
        bindNext(unboxed, bFirst, bRest) match {
          case null =>
            return (if (current ne null) current else Now(unboxed)).asInstanceOf[Eager[A]]
          case bind =>
            current = bind
            hasUnboxed = false
            unboxed = null
            bFirst = null
        }
      }
    } while (true)
    // $COVERAGE-OFF$
    null // Unreachable code
    // $COVERAGE-ON$
  }

  private def tryRecoverError(error: Throwable, bFirst: Bind, bRest: CallStack): Coeval[Any] = {
    var result: Coeval[Any] = null
    var cursor = bFirst
    var continue = true

    while (continue) {
      cursor match {
        case FlatMap(_, _, g) if g != null =>
          try { result = g(error) }
          catch { case NonFatal(e) => result = Error(e) }
          continue = false
        case _ =>
          cursor = if (bRest ne null) bRest.pop() else null
          continue = cursor != null
      }
    }
    result
  }

  private def bindNext(value: Any, bFirst: Bind, bRest: CallStack): Coeval[Any] = {
    var result: Coeval[Any] = null
    var cursor = bFirst
    var continue = true

    while (continue) {
      cursor match {
        case FlatMap(_, f, _) if f != null =>
          try { result = f(value) }
          catch { case NonFatal(e) => result = Error(e) }
          continue = false
        case Map(_, f, _) =>
          try { result = Now(f(value)) }
          catch { case NonFatal(e) => result = Error(e) }
          continue = false
        case _ =>
          cursor = if (bRest ne null) bRest.pop() else null
          continue = cursor != null
      }
    }
    result
  }

  private def createCallStack(): CallStack =
    ArrayStack(8)
}
