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

package monix.execution

//import cats.Monad
import minitest.TestSuite
//import monix.execution.exceptions.DummyException
import monix.execution.schedulers.TestScheduler
//import scala.concurrent.duration._
import scala.util.{Failure, Success}

object FastFutureSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(env: TestScheduler): Unit =
    assert(env.state.tasks.isEmpty, "should not have tasks left to execute")

  test("FastFuture.fromTry(success)") { implicit s =>
    val f = FastFuture.fromTry(Success(1))
    assertEquals(f.value, Some(Success(1)))
  }

  test("FastFuture.fromTry(failure)") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = FastFuture.fromTry(Failure(ex))
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("FastFuture.successful is already completed") { implicit s =>
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

//  test("now.map.transform") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1).transform(_+1, ex => ex)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(3)))
//  }
//
//  test("now.map") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("async.map") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).map(_+1)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.map.map") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1).map(_+1)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(3)))
//  }
//
//  test("now.flatMap") { implicit s =>
//    val f = FastFuture.successful(1)
//      .flatMap(x => FastFuture.successful(x+1))
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("async.flatMap") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty)
//      .flatMap(x => FastFuture.successful(x+1))
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.map.flatMap") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1)
//      .flatMap(x => FastFuture.successful(x+1))
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(3)))
//  }
//
//  test("now.filter") { implicit s =>
//    val f = FastFuture.successful(1).filter(_ == 1)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("async.filter") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).filter(_ == 1)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.map.filter") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1).filter(_ == 2)
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.collect") { implicit s =>
//    val f = FastFuture.successful(1).collect { case x => x + 1 }
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("async.collect") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).collect { case x => x + 1 }
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.map.collect") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1).collect { case x => x + 1 }
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(3)))
//  }
//
//  test("now.failed") { implicit s =>
//    val dummy = new RuntimeException("dummy")
//    val f = FastFuture.failed(dummy).failed
//    s.tick(); assertEquals(f.value, Some(Success(dummy)))
//  }
//
//  test("async.failed") { implicit s =>
//    val dummy = new RuntimeException("dummy")
//    val f = FastFuture(Future(throw dummy), Cancelable.empty).failed
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(dummy)))
//  }
//
//  test("now.recover") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture.failed(ex).recover { case _ => 1 }
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("async.recover") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture(Future(throw ex), Cancelable.empty).recover { case _ => 1 }
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.map.recover") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture.failed[Int](ex).map(_+1).recover { case _ => 1 }
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.recoverWith") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture.failed(ex)
//      .recoverWith { case _ => FastFuture.successful(1) }
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("async.recoverWith") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture(Future(throw ex), Cancelable.empty)
//      .recoverWith { case _ => FastFuture.successful(1) }
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.map.recoverWith") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture.failed[Int](ex).map(_+1)
//      .recoverWith { case _ => FastFuture.successful(1) }
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.zip(now)") { implicit s =>
//    val f = FastFuture.successful(1)
//      .zip(FastFuture.successful(1))
//      .map { case (x,y) => x + y }
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("async.zip(Async)") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty)
//      .zip(FastFuture(Future(1), Cancelable.empty))
//      .map { case (x,y) => x + y }
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.map.zip(now.map)") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1)
//      .zip(FastFuture.successful(1).map(_+1))
//      .map { case (x,y) => x + y }
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(4)))
//  }
//
//  test("now.fallbackTo") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture.failed(ex)
//      .fallbackTo(FastFuture.successful(1))
//
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("async.fallbackTo") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture(Future(throw ex), Cancelable.empty)
//      .fallbackTo(FastFuture.successful(1))
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.map.fallbackTo") { implicit s =>
//    val ex = new RuntimeException("dummy")
//    val f = FastFuture.failed[Int](ex).map(_+1)
//      .fallbackTo(FastFuture.successful(1))
//
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.mapTo") { implicit s =>
//    val f = FastFuture.successful(1).mapTo[Int]
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("async.mapTo") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).mapTo[Int]
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.map.mapTo") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1).mapTo[Int]
//    assertEquals(f.value, None)
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.andThen") { implicit s =>
//    val f = FastFuture.successful(1).andThen { case Success(x) => x+1 }
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("async.andThen") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).andThen { case Success(x) => x+1 }
//    s.tick()
//    assertEquals(f.value, Some(Success(1)))
//  }
//
//  test("now.map.andThen") { implicit s =>
//    val f = FastFuture.successful(1).map(_+1).andThen { case Success(x) => x+1 }
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.transform") { implicit s =>
//    val f = FastFuture.successful(1).transform {
//      case Success(value) => Success(value + 1)
//      case error @ Failure(_) => error
//    }
//
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("now.transformWith") { implicit s =>
//    val f = FastFuture.successful(1).transformWith {
//      case Success(value) => Future.successful(value + 1)
//      case Failure(ex) => Future.failed(ex)
//    }
//
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("error.transform") { implicit s =>
//    val ex = DummyException("dummy")
//    val f = FastFuture.failed[Int](ex).transform {
//      case Failure(`ex`) => Success(10)
//      case other @ Failure(_) => other
//      case Success(value) => Success(value + 1)
//    }
//
//    s.tick()
//    assertEquals(f.value, Some(Success(10)))
//  }
//
//  test("error.transformWith") { implicit s =>
//    val ex = DummyException("dummy")
//    val f = FastFuture.failed[Int](ex).transformWith {
//      case Failure(`ex`) => Future.successful(10)
//      case Failure(other) => Future.failed(other)
//      case Success(value) => Future.successful(value + 1)
//    }
//
//    s.tick()
//    assertEquals(f.value, Some(Success(10)))
//  }
//
//  test("async.transform") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).transform {
//      case Success(value) => Success(value + 1)
//      case error @ Failure(_) => error
//    }
//
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("async.transformWith") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty).transformWith {
//      case Success(value) => Future.successful(value + 1)
//      case Failure(ex) => Future.failed(ex)
//    }
//
//    s.tick()
//    assertEquals(f.value, Some(Success(2)))
//  }
//
//  test("async.isCompleted") { implicit s =>
//    val f = FastFuture(Future(1), Cancelable.empty)
//    assert(!f.isCompleted, "!f.isCompleted")
//    s.tick()
//    assert(f.isCompleted, "f.isCompleted")
//  }
//
//  test("never") { implicit s =>
//    var effect = false
//    val f = FastFuture.never[Int]
//    f.onComplete(_ => effect = true)
//
//    s.tick()
//    assert(!effect, "!effect")
//    assert(!f.isCompleted, "!f.isCompleted")
//    assertEquals(f.value, None)
//
//    f.cancel()
//    assertEquals(f.value, None)
//  }
//
//  test("flatMap cancels first") { implicit s =>
//    val c = BooleanCancelable()
//    val f = FastFuture(FastFuture.never[Unit], c)
//      .flatMap(_ => Future.successful(1))
//
//    assert(!f.isCompleted, "f.isCompleted")
//    s.tick()
//    f.cancel()
//    assert(c.isCanceled, "c.isCanceled")
//  }
//
//  test("flatMap cancels second") { implicit s =>
//    val c = BooleanCancelable()
//    val first = FastFuture.successful(())
//
//    val f = first.flatMap { _ =>
//      FastFuture(FastFuture.never[Unit], Cancelable { () =>
//        c.cancel()
//      })
//    }
//
//    assert(first.isCompleted, "!first.isCompleted")
//    s.tick()
//    f.cancel()
//    s.tick()
//    assert(c.isCanceled, "c.isCanceled")
//  }
//
//  test("flatMap cancels third") { implicit s =>
//    val c = BooleanCancelable()
//    val first = FastFuture(Future.successful(1), Cancelable.empty)
//
//    val f = first.flatMap { _ =>
//      val second = FastFuture(Future.successful(2), Cancelable.empty)
//      second.flatMap { _ =>
//        FastFuture(FastFuture.never[Unit], c)
//      }
//    }
//
//    assert(first.isCompleted, "!first.isCompleted")
//    s.tick()
//    f.cancel()
//    s.tick()
//    assert(c.isCanceled, "c.isCanceled")
//  }
//
//  test("flatMap should be stack safe") { implicit s =>
//    val n = 100000
//    val M = Monad[FastFuture]
//    val f = M.tailRecM(0)(i => M.pure(if (i < n) Left(i + 1) else Right(i)))
//    s.tick()
//    assert(f.isCompleted, "!f.isCompleted")
//    assertEquals(f.value, Some(Success(n)))
//  }
//
//  test("async works for success") { implicit s =>
//    val fa = FastFuture.async[Int] { cb =>
//      s.executeAsync(() => cb(Success(1)))
//      Cancelable.empty
//    }
//
//    s.tick()
//    assertEquals(fa.value, Some(Success(1)))
//  }
//
//  test("async works for failure") { implicit s =>
//    val dummy = DummyException("dummy")
//    val fa = FastFuture.async[Int] { cb =>
//      s.executeAsync(() => cb(Failure(dummy)))
//      Cancelable.empty
//    }
//
//    s.tick()
//    assertEquals(fa.value, Some(Failure(dummy)))
//  }
//
//  test("async is cancelable") { implicit s =>
//    val fa = FastFuture.async[Int] { cb =>
//      s.scheduleOnce(1.second)(cb(Success(1)))
//    }
//
//    s.tick()
//    assertEquals(fa.value, None)
//    assert(s.state.tasks.nonEmpty, "tasks.nonEmpty")
//
//    fa.cancel()
//    s.tick()
//    assertEquals(fa.value, None)
//    assert(s.state.tasks.isEmpty, "tasks.isEmpty")
//
//    s.tick(1.second)
//    assertEquals(fa.value, None)
//  }
//
//  test("async throws error if protocol is violated") { implicit s =>
//    val fa = FastFuture.async[Int] { cb =>
//      cb(Success(1))
//      cb(Success(2))
//      Cancelable.empty
//    }
//
//    assert(fa.value.isDefined && fa.value.get.isSuccess)
//    assert(s.state.lastReportedError != null)
//    assert(s.state.lastReportedError.isInstanceOf[IllegalStateException])
//  }
//
//  test("transform is safe") { implicit s =>
//    val fa1 = FastFuture(FastFuture.successful(1), Cancelable.empty)
//    val fa2 = fa1.transform(_.map(_ + 1))
//    val fa3 = fa2.transform(_.map(_ + 1))
//
//    s.tick()
//    assertEquals(fa1.value, Some(Success(1)))
//    assertEquals(fa2.value, Some(Success(2)))
//    assertEquals(fa3.value, Some(Success(3)))
//  }
//
//  test("transformWith is safe") { implicit s =>
//    val fa1 = FastFuture(FastFuture.successful(1), Cancelable.empty)
//    val fa2 = fa1.transformWith(x => FastFuture.fromTry(x.map(_ + 1)))
//    val fa3 = fa2.transformWith(x => FastFuture.fromTry(x.map(_ + 1)))
//
//    s.tick()
//    assertEquals(fa1.value, Some(Success(1)))
//    assertEquals(fa2.value, Some(Success(2)))
//    assertEquals(fa3.value, Some(Success(3)))
//  }
//
//  test("transform protects against user error") { implicit s =>
//    val dummy = DummyException("dummy")
//    val fa1 = FastFuture(FastFuture.successful(1), Cancelable.empty)
//    val fa2 = fa1.transform(_ => throw dummy)
//
//    s.tick()
//    assertEquals(fa2.value, Some(Failure(dummy)))
//  }
//
//  test("transformWith protects against user error") { implicit s =>
//    val dummy = DummyException("dummy")
//    val fa1 = FastFuture(FastFuture.successful(1), Cancelable.empty)
//    val fa2 = fa1.transformWith(_ => throw dummy)
//
//    s.tick()
//    assertEquals(fa2.value, Some(Failure(dummy)))
//  }
//
//  test("pure.transform protects against user error") { implicit s =>
//    val dummy = DummyException("dummy")
//    val fa1 = FastFuture.successful(1)
//    val fa2 = fa1.transform(_ => throw dummy)
//
//    s.tick()
//    assertEquals(fa2.value, Some(Failure(dummy)))
//  }
//
//  test("pure.transformWith protects against user error") { implicit s =>
//    val dummy = DummyException("dummy")
//    val fa1 = FastFuture.pure(1)
//    val fa2 = fa1.transformWith(_ => throw dummy)
//
//    s.tick()
//    assertEquals(fa2.value, Some(Failure(dummy)))
//  }
//
//  test("raiseError.transform protects against user error") { implicit s =>
//    val dummy1 = DummyException("dummy1")
//    val dummy2 = DummyException("dummy2")
//
//    val fa1 = FastFuture.raiseError(dummy1)
//    val fa2 = fa1.transform(_ => throw dummy2)
//
//    s.tick()
//    assertEquals(fa2.value, Some(Failure(dummy2)))
//  }
//
//  test("pure.transformWith protects against user error") { implicit s =>
//    val dummy1 = DummyException("dummy1")
//    val dummy2 = DummyException("dummy2")
//
//    val fa1 = FastFuture.raiseError(dummy1)
//    val fa2 = fa1.transformWith(_ => throw dummy2)
//
//    s.tick()
//    assertEquals(fa2.value, Some(Failure(dummy2)))
//  }
}
