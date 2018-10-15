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

package monix.catnap

import cats.effect.{Async, IO, ContextShift}
import minitest.TestSuite
import monix.catnap.syntax._
import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler
import monix.execution.{Cancelable, CancelableFuture}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object LiftFutureSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(env: TestScheduler): Unit =
    assert(env.state.tasks.isEmpty, "There should be no tasks left!")

  implicit def contextShift(implicit ec: TestScheduler) =
    ec.contextShift[IO]
  
  test("IO(future).liftFuture") { implicit s =>
    var effect = 0
    val io = IO(Future { effect += 1; effect }).liftFuture

    val f1 = io.unsafeToFuture(); s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = io.unsafeToFuture(); s.tick()
    assertEquals(f2.value, Some(Success(2)))
  }

  test("IO(Future.successful).liftFuture") { implicit s =>
    val io = IO(Future.successful(1)).liftFuture

    val f1 = io.unsafeToFuture(); s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = io.unsafeToFuture(); s.tick()
    assertEquals(f2.value, Some(Success(1)))
  }

  test("IO(Future.failed).liftFuture") { implicit s =>
    val dummy = DummyException("dummy")
    val io = IO(Future.failed[Int](dummy)).liftFuture

    val f1 = io.unsafeToFuture(); s.tick()
    assertEquals(f1.value, Some(Failure(dummy)))
    val f2 = io.unsafeToFuture(); s.tick()
    assertEquals(f2.value, Some(Failure(dummy)))
  }

  test("F.delay(future).liftFuture for Async[F] data types") { implicit s =>
    import Overrides.asyncIO
    var effect = 0

    def mkInstance[F[_]: Async] =
      Async[F].delay(Future { effect += 1; effect }).liftFuture

    val io = mkInstance[IO]
    val f1 = io.unsafeToFuture(); s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = io.unsafeToFuture(); s.tick()
    assertEquals(f2.value, Some(Success(2)))
  }

  test("F.delay(Future.successful).liftFuture for Async[F] data types") { implicit s =>
    import Overrides.asyncIO

    def mkInstance[F[_]: Async] =
      Async[F].delay(Future.successful(1)).liftFuture

    val io = mkInstance[IO]
    val f1 = io.unsafeToFuture(); s.tick()
    assertEquals(f1.value, Some(Success(1)))
    val f2 = io.unsafeToFuture(); s.tick()
    assertEquals(f2.value, Some(Success(1)))
  }

  test("F.delay(Future.failed).liftFuture for Async[F] data types") { implicit s =>
    import Overrides.asyncIO

    val dummy = DummyException("dummy")
    def mkInstance[F[_]: Async] =
      Async[F].delay(Future.failed[Int](dummy)).liftFuture

    val io = mkInstance[IO]
    val f1 = io.unsafeToFuture(); s.tick()
    assertEquals(f1.value, Some(Failure(dummy)))
    val f2 = io.unsafeToFuture(); s.tick()
    assertEquals(f2.value, Some(Failure(dummy)))
  }

  test("F.delay(future).liftFuture for Concurrent[F] data types") { implicit s =>
    var wasCanceled = 0
    val io = IO(CancelableFuture[Int](CancelableFuture.never, Cancelable { () => wasCanceled += 1 }))
      .liftFuture

    val p = Promise[Int]()
    val token = io.unsafeRunCancelable {
      case Left(e) => p.failure(e)
      case Right(a) => p.success(a)
    }

    // Cancelling
    token.unsafeRunAsyncAndForget(); s.tick()
    assertEquals(wasCanceled, 1)
  }
}
