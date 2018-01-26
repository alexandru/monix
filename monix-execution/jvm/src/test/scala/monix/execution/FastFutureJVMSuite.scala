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

package monix.execution

import minitest.SimpleTestSuite
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration.Duration

object FastFutureJVMSuite extends SimpleTestSuite {
  test("block for async future completion") {
    for (_ <- 0 until 1000) {
      val f = FastFuture(1)
      assertEquals(Await.result(f, Duration.Inf), 1)
    }
  }

  test("block successful future completion") {
    for (_ <- 0 until 1000) {
      val f = FastFuture.successful(1)
      assertEquals(Await.result(f, Duration.Inf), 1)
    }
  }

  test("block for FastFuture.never") {
    intercept[TimeoutException] {
      Await.result(FastFuture.never, Duration.Inf)
    }
    intercept[TimeoutException] {
      Await.ready(FastFuture.never, Duration.Inf)
    }
  }

  test("stack safety for async futures") {
    def loop(n: Int, acc: Int): FastFuture[Int] =
      FastFuture(n).flatMap { x =>
        if (x > 0) loop(n - 1, acc + 1)
        else FastFuture(acc)
      }

    assertEquals(Await.result(loop(100000, 0), Duration.Inf), 100000)
  }
}
