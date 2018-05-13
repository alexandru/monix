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

import cats.effect.IO
import cats.effect.laws.discipline._
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline.{ApplicativeTests, CoflatMapTests, ParallelTests}
import cats.{Applicative, Eq}
import monix.eval.instances.CatsParallelForTask
import monix.execution.{Scheduler, UncaughtExceptionReporter}

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.util.Try

/** Type class tests for Task that use an alternative `Eq`, making
  * use of Task's `runAsync(callback)`.
  */
object TypeClassLawsForTaskRunSyncUnsafeSuite extends monix.execution.BaseLawsSuite
  with  ArbitraryInstancesBase {

  implicit val sc = Scheduler(global, UncaughtExceptionReporter(_ => ()))
  implicit val ap: Applicative[Task.Par] = CatsParallelForTask.applicative

  val timeout = {
    if (System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true")
      5.minutes
    else
      10.seconds
  }

  implicit val params = Parameters.default.copy(
    // Disabling non-terminating tests (that test equivalence with Task.never)
    // because they'd behave really badly with an Eq[Task] that depends on
    // blocking threads
    allowNonTerminationLaws = false)

  implicit def equalityTask[A](implicit A: Eq[A]): Eq[Task[A]] =
    Eq.instance { (a, b) =>
      val ta = Try(a.runSyncUnsafe(timeout))
      val tb = Try(b.runSyncUnsafe(timeout))
      equalityTry[A].eqv(ta, tb)
    }

  implicit def equalityTaskPar[A](implicit A: Eq[A]): Eq[Task.Par[A]] =
    Eq.instance { (a, b) =>
      import Task.Par.unwrap
      val ta = Try(unwrap(a).runSyncUnsafe(timeout))
      val tb = Try(unwrap(b).runSyncUnsafe(timeout))
      equalityTry[A].eqv(ta, tb)
    }

  implicit def equalityIO[A](implicit A: Eq[A]): Eq[IO[A]] =
    Eq.instance { (a, b) =>
      val ta = Try(a.unsafeRunSync())
      val tb = Try(b.unsafeRunSync())
      equalityTry[A].eqv(ta, tb)
    }

  checkAll("CoflatMap[Task]",
    CoflatMapTests[Task].coflatMap[Int,Int,Int])

  checkAll("Concurrent[Task]",
    ConcurrentTests[Task].async[Int,Int,Int])

  checkAll("ConcurrentEffect[Task]",
    ConcurrentEffectTests[Task].effect[Int,Int,Int])

  checkAll("Applicative[Task.Par]",
    ApplicativeTests[Task.Par].applicative[Int, Int, Int])

  checkAll("Parallel[Task, Task.Par]",
    ParallelTests[Task, Task.Par].parallel[Int, Int])

  checkAll("Monoid[Task[Int]]",
    MonoidTests[Task[Int]].monoid)
}
