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

package monix.execution.internal

import java.util.concurrent.locks.AbstractQueuedSynchronizer
import monix.execution.schedulers.TrampolineExecutionContext.immediate
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration.{Duration, FiniteDuration}

private[execution] object BlockingOps {
  /** Blocks for the result of a `Future`.
    *
    * JVM-specific implementation, since blocking is not supported
    * on top of JavaScript.
    */
  def awaitForFuture[A](fa: Future[A], limit: Duration): Unit =
    if (!fa.isCompleted) {
      val latch = new OneShotLatch
      fa.onComplete(_ => latch.releaseShared(1))(immediate)

      limit match {
        case e if e eq Duration.Undefined =>
          throw new IllegalArgumentException("Cannot wait for Undefined period")
        case Duration.Inf =>
          blocking(latch.acquireSharedInterruptibly(1))
        case f: FiniteDuration if f > Duration.Zero =>
          blocking(latch.tryAcquireSharedNanos(1, f.toNanos))
        case _ =>
          () // Do nothing
      }
    }

  private final class OneShotLatch extends AbstractQueuedSynchronizer {
    override protected def tryAcquireShared(ignored: Int): Int =
      if (getState != 0) 1 else -1

    override protected def tryReleaseShared(ignore: Int): Boolean = {
      setState(1)
      true
    }
  }
}
