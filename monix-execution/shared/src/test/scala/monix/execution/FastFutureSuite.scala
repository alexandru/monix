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

import cats.Monad
import minitest.TestSuite
import monix.execution.exceptions.DummyException

import scala.concurrent.Future
import monix.execution.schedulers.TestScheduler
import monix.execution.internal.Platform

import scala.util.{Failure, Success, Try}

object FastFutureSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(env: TestScheduler): Unit =
    assert(env.state.tasks.isEmpty, "should not have tasks left to execute")

  val stackSafetyN: Int =
    if (Platform.isJVM) 20000 else 5000

  test("fromTry(success)") { implicit s =>
    val f = FastFuture.fromTry(Success(1))
    assertEquals(f.value, Some(Success(1)))
  }

  test("fromTry(failure)") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.fromTry(Failure(ex))
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("eval(success)") { implicit s =>
    val f = FastFuture.eval(1).map(_ + 1)
    assertEquals(f.value, Some(Success(2)))
  }

  test("eval(failure)") { implicit s =>
    val ex = new DummyException("dummy")
    val f = FastFuture.eval[Int](throw ex).map(_ + 1)
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("apply(success)") { implicit s =>
    val f = FastFuture(1).map(_ + 1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("apply(failure)") { implicit s =>
    val ex = new DummyException("dummy")
    val f = FastFuture[Int](throw ex).map(_ + 1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("successful is already completed") { implicit s =>
    val f = FastFuture.successful(1)
    assertEquals(f.isCompleted, true)
    assertEquals(f.value, Some(Success(1)))
    val f2 = f.failed.value
    assert(f2.isDefined && f2.get.isFailure, "f.failed should be completed as well")
  }

  test("now.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = FastFuture.failed(dummy).failed
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("async.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = FastFuture.async[Int](_(Failure(dummy))).failed
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("now.map.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = FastFuture.failed[Int](dummy).map(_+1).failed
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("now.transform") { implicit s =>
    val f = FastFuture.successful(1).transform(_+1, ex => ex)
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.transform") { implicit s =>
    val f = FastFuture.async[Int](_(Success(1))).transform(_ + 1, ex => ex)
    assertEquals(f.value, Some(Success(2)))
  }

  test("stack safety for pure") { implicit s =>
    def loop(n: Int): FastFuture[Int] =
      FastFuture.pure(n).flatMap { x =>
        if (x > 0) loop(x - 1)
        else FastFuture.pure(0)
      }

    val f = loop(stackSafetyN)
    assert(f.isCompleted, "f.isCompleted")
    assertEquals(f.value, Some(Success(0)))
  }

  test("stack safety for eval") { implicit s =>
    def loop(n: Int): FastFuture[Int] =
      FastFuture.eval(n).flatMap { x =>
        if (x > 0) loop(x - 1)
        else FastFuture.eval(0)
      }

    val f = loop(stackSafetyN)
    assert(f.isCompleted, "f.isCompleted")
    assertEquals(f.value, Some(Success(0)))
  }

  test("stack safety for promise, test 1") { implicit s =>
    def loop(n: Int): FastFuture[Int] =
      FastFuture.eval(n).flatMap { x =>
        if (x > 0) loop(x - 1)
        else FastFuture.eval(0)
      }

    val p = FastFuture.promise[Int]
    val f = p.flatMap(loop)

    assert(!f.isCompleted, "!f.isCompleted")
    p.complete(Success(stackSafetyN))

    assert(f.isCompleted, "f.isCompleted")
    assertEquals(f.value, Some(Success(0)))
  }


  test("stack safety for promise, test 2") { implicit s =>
    def loop(f: FastFuture[Int]): FastFuture[Int] =
      f.flatMap { x =>
        if (x > 0) {
          val p = FastFuture.promise[Int]
          s.executeAsync(() => p.complete(Success(x - 1)))
          loop(p)
        }
        else
          FastFuture.eval(0)
      }

    val f = loop(FastFuture.eval(stackSafetyN))
    s.tick()

    assert(f.isCompleted, "f.isCompleted")
    assertEquals(f.value, Some(Success(0)))
  }

  test("stack safety in `tailRecM`") { implicit s =>
    val n = 100000
    val M = Monad[FastFuture]
    val f = M.tailRecM(0)(i => M.pure(if (i < n) Left(i + 1) else Right(i)))

    assert(f.isCompleted, "f.isCompleted")
    assertEquals(f.value, Some(Success(n)))
  }

  test("now.map.transform") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).transform(_+1, ex => ex)
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.map") { implicit s =>
    val f = FastFuture.successful(1).map(_+1)
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.map") { implicit s =>
    val f = FastFuture.eval(1).map(_+1)
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.map") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).map(_+1)
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.flatMap") { implicit s =>
    val f = FastFuture.successful(1).flatMap(x => FastFuture.eval(x+1))
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.flatMap") { implicit s =>
    val f = FastFuture.eval(1).flatMap(x => FastFuture.eval(x+1))
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.flatMap") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).flatMap(x => FastFuture.successful(x+1))
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.filter") { implicit s =>
    val f = FastFuture.successful(1).filter(_ == 1)
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.filter") { implicit s =>
    val f = FastFuture.eval(1).filter(_ == 1)
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.filter") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).filter(_ == 2)
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.collect") { implicit s =>
    val f = FastFuture.successful(1).collect { case x => x + 1 }
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.collect") { implicit s =>
    val f = FastFuture.eval(1).collect { case x => x + 1 }
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.collect") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).collect { case x => x + 1 }
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = FastFuture.failed(dummy).failed
    s.tick(); assertEquals(f.value, Some(Success(dummy)))
  }

  test("async.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = FastFuture.eval(throw dummy).failed
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("now.recover") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.failed(ex).recover { case _ => 1 }
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.recover") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.eval(throw ex).recover { case _ => 1 }
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.recover") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.failed[Int](ex).map(_+1).recover { case _ => 1 }
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.recoverWith") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.failed(ex).recoverWith { case _ => FastFuture.eval(1) }
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.recoverWith") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.eval(throw ex).recoverWith { case _ => FastFuture.successful(1) }
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.recoverWith") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.failed[Int](ex).map(_+1)
      .recoverWith { case _ => FastFuture.successful(1) }

    assertEquals(f.value, Some(Success(1)))
  }

  test("now.zip(now)") { implicit s =>
    val f = FastFuture.successful(1)
      .zip(FastFuture.successful(1))
      .map { case (x,y) => x + y }

    assertEquals(f.value, Some(Success(2)))
  }

  test("async.zip(Async)") { implicit s =>
    val f = FastFuture.eval(1)
      .zip(FastFuture.eval(1))
      .map { case (x,y) => x + y }

    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.zip(now.map)") { implicit s =>
    val f = FastFuture.successful(1).map(_+1)
      .zip(FastFuture.successful(1).map(_+1))
      .map { case (x,y) => x + y }

    assertEquals(f.value, Some(Success(4)))
  }

  test("now.fallbackTo") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.failed(ex)
      .fallbackTo(FastFuture.successful(1))

    assertEquals(f.value, Some(Success(1)))
  }

  test("async.fallbackTo") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.eval(throw ex)
      .fallbackTo(FastFuture.successful(1))

    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.fallbackTo") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.failed[Int](ex).map(_+1)
      .fallbackTo(FastFuture.successful(1))

    assertEquals(f.value, Some(Success(1)))
  }

  test("now.mapTo") { implicit s =>
    val f = FastFuture.successful(1).mapTo[Int]
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.mapTo") { implicit s =>
    val f = FastFuture.eval(1).mapTo[Int]
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.mapTo") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).mapTo[Int]
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.andThen") { implicit s =>
    val f = FastFuture.successful(1).andThen { case Success(x) => x+1 }
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.andThen") { implicit s =>
    val f = FastFuture.eval(1).andThen { case Success(x) => x+1 }
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.andThen") { implicit s =>
    val f = FastFuture.successful(1).map(_+1).andThen { case Success(x) => x+1 }
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.transform") { implicit s =>
    val f = FastFuture.successful(1).transform {
      case Success(value) => Success(value + 1)
      case error @ Failure(_) => error
    }

    assertEquals(f.value, Some(Success(2)))
  }

  test("now.transformWith") { implicit s =>
    val f = FastFuture.successful(1).transformWith {
      case Success(value) => Future.successful(value + 1)
      case Failure(ex) => Future.failed(ex)
    }

    assertEquals(f.value, Some(Success(2)))
  }

  test("error.transform") { implicit s =>
    val ex = DummyException("dummy")
    val f = FastFuture.failed[Int](ex).transform {
      case Failure(`ex`) => Success(10)
      case other @ Failure(_) => other
      case Success(value) => Success(value + 1)
    }

    assertEquals(f.value, Some(Success(10)))
  }

  test("error.transformWith") { implicit s =>
    val ex = DummyException("dummy")
    val f = FastFuture.failed[Int](ex).transformWith {
      case Failure(`ex`) => Future.successful(10)
      case Failure(other) => Future.failed(other)
      case Success(value) => Future.successful(value + 1)
    }

    assertEquals(f.value, Some(Success(10)))
  }

  test("async.transform") { implicit s =>
    val f = FastFuture.eval(1).transform {
      case Success(value) => Success(value + 1)
      case error @ Failure(_) => error
    }

    assertEquals(f.value, Some(Success(2)))
  }

  test("async.transformWith") { implicit s =>
    val f = FastFuture.eval(1).transformWith {
      case Success(value) => Future.successful(value + 1)
      case Failure(ex) => Future.failed(ex)
    }

    assertEquals(f.value, Some(Success(2)))
  }

  test("async.isCompleted") { implicit s =>
    val f = FastFuture.eval(1)
    assert(f.isCompleted, "f.isCompleted")
  }

  test("never") { implicit s =>
    var effect = false
    val f = FastFuture.never[Int]
    f.onComplete(_ => effect = true)

    assert(!effect, "!effect")
    assert(!f.isCompleted, "!f.isCompleted")
    assertEquals(f.value, None)
  }

  test("async works for success") { implicit s =>
    val fa = FastFuture.async[Int] { cb =>
      s.executeTrampolined(() => cb(Success(1)))
    }

    assertEquals(fa.value, Some(Success(1)))
  }

  test("async works for failure") { implicit s =>
    val dummy = DummyException("dummy")
    val fa = FastFuture.async[Int] { cb =>
      s.executeTrampolined(() => cb(Failure(dummy)))
    }

    assertEquals(fa.value, Some(Failure(dummy)))
  }

  test("async throws error if protocol is violated") { implicit s =>
    val fa = FastFuture.async[Int] { cb =>
      cb(Success(1))
      cb(Success(2))
    }

    assert(fa.value.isDefined && fa.value.get.isSuccess)
    assert(s.state.lastReportedError != null)
    assert(s.state.lastReportedError.isInstanceOf[IllegalStateException])
  }

  test("transform is safe") { implicit s =>
    val fa1 = FastFuture.eval(1)
    val fa2 = fa1.transform(_.map(_ + 1))
    val fa3 = fa2.transform(_.map(_ + 1))

    assertEquals(fa1.value, Some(Success(1)))
    assertEquals(fa2.value, Some(Success(2)))
    assertEquals(fa3.value, Some(Success(3)))
  }

  test("transformWith is safe") { implicit s =>
    val fa1 = FastFuture.eval(1)
    val fa2 = fa1.transformWith(x => FastFuture.fromTry(x.map(_ + 1)))
    val fa3 = fa2.transformWith(x => FastFuture.fromTry(x.map(_ + 1)))

    assertEquals(fa1.value, Some(Success(1)))
    assertEquals(fa2.value, Some(Success(2)))
    assertEquals(fa3.value, Some(Success(3)))
  }

  test("transform protects against user error") { implicit s =>
    val dummy = DummyException("dummy")
    val fa1 = FastFuture.eval(1)
    val fa2 = fa1.transform(_ => throw dummy)

    assertEquals(fa2.value, Some(Failure(dummy)))
  }

  test("transformWith protects against user error") { implicit s =>
    val dummy = DummyException("dummy")
    val fa1 = FastFuture.eval(1)
    val fa2 = fa1.transformWith(_ => throw dummy)

    assertEquals(fa2.value, Some(Failure(dummy)))
  }

  test("pure.transform protects against user error") { implicit s =>
    val dummy = DummyException("dummy")
    val fa1 = FastFuture.successful(1)
    val fa2 = fa1.transform(_ => throw dummy)

    assertEquals(fa2.value, Some(Failure(dummy)))
  }

  test("pure.transformWith protects against user error") { implicit s =>
    val dummy = DummyException("dummy")
    val fa1 = FastFuture.pure(1)
    val fa2 = fa1.transformWith(_ => throw dummy)

    assertEquals(fa2.value, Some(Failure(dummy)))
  }

  test("raiseError.transform protects against user error") { implicit s =>
    val dummy1 = DummyException("dummy1")
    val dummy2 = DummyException("dummy2")

    val fa1 = FastFuture.raiseError(dummy1)
    val fa2 = fa1.transform(_ => throw dummy2)

    assertEquals(fa2.value, Some(Failure(dummy2)))
  }

  test("pure.transformWith protects against user error") { implicit s =>
    val dummy1 = DummyException("dummy1")
    val dummy2 = DummyException("dummy2")

    val fa1 = FastFuture.raiseError(dummy1)
    val fa2 = fa1.transformWith(_ => throw dummy2)

    assertEquals(fa2.value, Some(Failure(dummy2)))
  }

  test("can handle multiple listeners for success") { implicit s =>
    val p = FastFuture.promise[Int]

    val f1 = p.map(_ + 1).map(_ + 1)
    val f2 = p.map(_ + 2).map(_ + 2)
    val f3 = p.map(_ + 3).map(_ + 3)

    assertEquals(f1.value, None)
    assertEquals(f2.value, None)
    assertEquals(f3.value, None)

    p.complete(Success(1))

    assertEquals(f1.value, Some(Success(3)))
    assertEquals(f2.value, Some(Success(5)))
    assertEquals(f3.value, Some(Success(7)))
  }

  test("can handle multiple listeners for failure") { implicit s =>
    val p = FastFuture.promise[Int]

    val f1 = p.map(_ + 1).map(_ + 1)
    val f2 = p.map(_ + 2).map(_ + 2)
    val f3 = p.map(_ + 3).map(_ + 3)

    assertEquals(f1.value, None)
    assertEquals(f2.value, None)
    assertEquals(f3.value, None)

    p.complete(Success(1))

    assertEquals(f1.value, Some(Success(3)))
    assertEquals(f2.value, Some(Success(5)))
    assertEquals(f3.value, Some(Success(7)))
  }

  test("promise.success") { implicit s =>
    val p = FastFuture.promise[Int]
    p.success(1)
    assertEquals(p.value, Some(Success(1)))

    intercept[IllegalStateException](p.success(1))
    assert(!p.tryUnsafeComplete(Success(2)), "!p.tryUnsafeComplete")
    assertEquals(p.value, Some(Success(1)))
  }

  test("promise.fail") { implicit s =>
    val p = FastFuture.promise[Int]
    val dummy = new DummyException("dummy")

    p.failure(dummy)
    assertEquals(p.value, Some(Failure(dummy)))

    intercept[IllegalStateException](p.failure(dummy))
    assert(!p.tryUnsafeComplete(Success(2)), "!p.tryUnsafeComplete")
    assertEquals(p.value, Some(Failure(dummy)))
  }

  test("completeWith") { implicit s =>
    val p = FastFuture.promise[Int]
    p.completeWith(Future(1))

    assertEquals(p.value, None)
    s.tick()
    assertEquals(p.value, Some(Success(1)))

    p.completeWith(Future(2))
    s.tick()
    assert(
      s.state.lastReportedError.isInstanceOf[IllegalStateException],
      "lastReportedError.isInstanceOf[IllegalStateException]")
  }

  test("tryUnsafeCompleteWith") { implicit s =>
    val p = FastFuture.promise[Int]
    p.tryUnsafeCompleteWith(Future(1))

    assertEquals(p.value, None)
    s.tick()
    assertEquals(p.value, Some(Success(1)))
  }

  test("convert success from regular async future") { implicit s =>
    val f = Future(1)
    val ff = FastFuture.fromFuture(f)

    assertEquals(ff.value, None)
    s.tick()
    assertEquals(ff.value, Some(Success(1)))
  }

  test("convert failure from regular async future") { implicit s =>
    val dummy = new DummyException("dummy")
    val f = Future(throw dummy)
    val ff = FastFuture.fromFuture(f)

    assertEquals(ff.value, None)
    s.tick()
    assertEquals(ff.value, Some(Failure(dummy)))
  }

  test("convert success from regular completed future") { implicit s =>
    val f = Future.successful(1)
    val ff = FastFuture.fromFuture(f)
    assertEquals(ff.value, Some(Success(1)))
  }

  test("convert failure from regular async future") { implicit s =>
    val dummy = new DummyException("dummy")
    val f = Future.failed(dummy)
    val ff = FastFuture.fromFuture(f)
    assertEquals(ff.value, Some(Failure(dummy)))
  }

  test("tryUnsafeComplete for flatMap-ed promise, in sequential order") { implicit s =>
    val p1 = FastFuture.promise[Int]
    val p2 = FastFuture.promise[Int]
    val p3 = FastFuture.promise[Int]

    val f = p1.flatMap(_ => p2.flatMap(_ => p3))
    assertEquals(f.value, None)

    p1.success(1)
    p2.success(2)
    p3.success(3)

    assertEquals(f.value, Some(Success(3)))
  }

  test("tryUnsafeComplete for flatMap-ed promise, in reverse order") { implicit s =>
    val p1 = FastFuture.promise[Int]
    val p2 = FastFuture.promise[Int]
    val p3 = FastFuture.promise[Int]

    val f = p1.flatMap(_ => p2.flatMap(_ => p3))
    assertEquals(f.value, None)

    p3.success(3)
    p2.success(2)
    p1.success(1)

    assertEquals(f.value, Some(Success(3)))
  }

  test("tryUnsafeCompleteWith with completed successful future") { implicit s =>
    val p = FastFuture.promise[Int]
    p.tryUnsafeCompleteWith(Future.successful(1))
    assertEquals(p.value, Some(Success(1)))
  }

  test("tryUnsafeCompleteWith with completed failed future") { implicit s =>
    val p = FastFuture.promise[Int]
    val dummy = new DummyException("dummy")
    p.tryUnsafeCompleteWith(Future.failed(dummy))
    assertEquals(p.value, Some(Failure(dummy)))
  }

  test(".value for flatMap-ed promise") { implicit s =>
    val p1 = FastFuture.promise[Int]
    val p2 = FastFuture.promise[Int]
    val f = p1.flatMap(_ => p2)

    assertEquals(f.value, None)
    assert(!f.isCompleted, "!f.isCompleted")

    p2.success(2)
    assertEquals(f.value, None)
    assert(!f.isCompleted, "!f.isCompleted")

    p1.success(1)
    assertEquals(f.value, Some(Success(2)))
    assert(f.isCompleted, "f.isCompleted")
  }

  test("onComplete is asynchronous for completed future") { implicit s =>
    val f = FastFuture.successful(1)
    var effect = Option.empty[Try[Int]]
    f.onComplete { r => effect = Some(r) }

    assertEquals(effect, None)
    s.tick()
    assertEquals(effect, Some(Success(1)))
  }

  test("onComplete is asynchronous for promise") { implicit s =>
    val f = FastFuture.promise[Int]
    var effect = Option.empty[Try[Int]]
    f.onComplete { r => effect = Some(r) }
    f.success(1)

    assertEquals(effect, None)
    s.tick()
    assertEquals(effect, Some(Success(1)))
  }

  test("onComplete reports errors") { implicit s =>
    val dummy = new DummyException("dummy")
    FastFuture.successful(1).onComplete(_ => throw dummy)
    s.tick()
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("onCompleteLight reports errors") { implicit s =>
    val dummy = new DummyException("dummy")
    FastFuture.successful(1).onCompleteLight(_ => throw dummy)
    assertEquals(s.state.lastReportedError, dummy)
  }

  test("promise already completed with success") { implicit s =>
    val p = FastFuture.LightPromise.fromTry(Success(1))
    assertEquals(p.value, Some(Success(1)))
    intercept[IllegalStateException] { p.success(2) }
  }

  test("promise already completed with failure") { implicit s =>
    val dummy = new DummyException("dummy")
    val p = FastFuture.LightPromise.fromTry[Int](Failure(dummy))
    assertEquals(p.value, Some(Failure(dummy)))
    intercept[IllegalStateException] { p.success(2) }
  }

  test("promises w/ one listener before completion") { implicit s =>
    var r1 = 0
    val p1 = FastFuture.promise[Int]
    p1.onCompleteLight { r => r1 += r.get }

    var r2 = 0
    val p2 = FastFuture.promise[Int]
    p2.onCompleteLight { r => r2 += r.get }

    var r3 = 0
    val p3 = FastFuture.promise[Int]
    p3.onCompleteLight { r => r3 += r.get }

    val f = p1.flatMap(_ => p2.flatMap(_ => p3))
    var effect = Option.empty[Try[Int]]
    f.onCompleteLight { r => effect = Some(r) }

    p1.success(1)
    p2.success(2)
    p3.success(3)

    assertEquals(effect, Some(Success(3)))
    assertEquals(r1, 1)
    assertEquals(r2, 2)
    assertEquals(r3, 3)
  }

  test("promises w/ multiple listeners before completion") { implicit s =>
    var r1 = 0
    val p1 = FastFuture.promise[Int]
    p1.onCompleteLight { r => r1 += r.get }
    p1.onCompleteLight { r => r1 += r.get }

    var r2 = 0
    val p2 = FastFuture.promise[Int]
    p2.onCompleteLight { r => r2 += r.get }
    p2.onCompleteLight { r => r2 += r.get }

    var r3 = 0
    val p3 = FastFuture.promise[Int]
    p3.onCompleteLight { r => r3 += r.get }
    p3.onCompleteLight { r => r3 += r.get }

    val f = p1.flatMap(_ => p2.flatMap(_ => p3))
    var effect = 0
    f.onCompleteLight { case Success(r) => effect += r; case _ => () }
    f.onCompleteLight { case Success(r) => effect += r; case _ => ()  }
    f.onCompleteLight { case Success(r) => effect += r; case _ => ()  }

    p1.success(1)
    p2.success(2)
    p3.success(3)

    assertEquals(effect, 9)
    assertEquals(r1, 2)
    assertEquals(r2, 4)
    assertEquals(r3, 6)
  }
}
