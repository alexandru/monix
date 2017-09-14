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

package monix.benchmarks

import java.util.concurrent.TimeUnit
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.FastFuture
import org.openjdk.jmh.annotations._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/** To run the benchmark from within SBT:
  *
  *     jmh:run -i 10 -wi 10 -f 2 -t 1 monix.benchmarks.FastFutureBenchmark
  *
  * Which means "10 iterations", "10 warm-up iterations", "2 fork", "1 thread".
  * Please note that benchmarks should be usually executed at least in
  * 10 iterations (as a rule of thumb), but more is better.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
class FastFutureBenchmark {
  @Param(Array("10000"))
  var size: Int = _

  @Benchmark
  def fastFutureLoop(): Long = {
    import FastFutureBenchmark.monixScheduler

    def loop(n: Int, acc: Long): FastFuture[Long] =
      FastFuture.successful(n).flatMap { n =>
        if (n <= 0) FastFuture.successful(n)
        else loop(n - 1, acc + 1)
      }

    Await.result(loop(size, 0), Duration.Inf)
  }

  @Benchmark
  def scalaFutureLoop(): Long = {
    import FastFutureBenchmark.monixScheduler

    def loop(n: Int, acc: Long): Future[Long] =
      Future.successful(n).flatMap { n =>
        if (n <= 0) Future.successful(n)
        else loop(n - 1, acc + 1)
      }

    Await.result(loop(size, 0), Duration.Inf)
  }

  @Benchmark
  def fastFutureAsyncLoop(): Long = {
    import FastFutureBenchmark.monixScheduler

    def loop(n: Int, acc: Long): FastFuture[Long] =
      FastFuture(n).flatMap { n =>
        if (n <= 0) FastFuture(n)
        else loop(n - 1, acc + 1)
      }

    Await.result(loop(size, 0), Duration.Inf)
  }

  @Benchmark
  def scalaFutureAsyncLoop(): Long = {
    import FastFutureBenchmark.monixScheduler

    def loop(n: Int, acc: Long): Future[Long] =
      Future(n).flatMap { n =>
        if (n <= 0) Future(n)
        else loop(n - 1, acc + 1)
      }

    Await.result(loop(size, 0), Duration.Inf)
  }
}

object FastFutureBenchmark {
  import monix.execution.Scheduler

  implicit val monixScheduler: Scheduler = {
    Scheduler.global.withExecutionModel(SynchronousExecution)
  }
}
