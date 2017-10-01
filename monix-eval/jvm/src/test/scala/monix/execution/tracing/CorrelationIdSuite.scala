package monix.execution.tracing

import minitest.SimpleTestSuite
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success

object CorrelationIdSuite extends SimpleTestSuite {


  case class Log(value: Int, message: String)

  def composed(implicit sc: Scheduler): Task[Log] = {
    for {
      a <- Task.fromFuture(Future(1))
      b <- Task.now(1)
      c <- Task.deferFuture(Future(1))
    } yield Log(a + b + c, CorrelationId.current.fold("")(x => s"Log this map ${x.id}"))
  }

  def taskFlatMap(implicit sc: Scheduler): Task[(Int, Option[CorrelationId])] = {
    for {
      a <- Task.fromFuture(Future(1))
      b <- Task.fromFuture(Future(1))
      c <- Task.fromFuture(Future(1))
      d <- Task.pure(CorrelationId.current)
    } yield (a + b + c, d)
  }

  def taskMapBoth(implicit sc: Scheduler): Task[Option[(String, String)]] =
    Task.mapBoth(pure, taskFlatMap) {
      case (p, (_, t)) =>
        for {
          a <- p
          b <- t
        } yield (a.id, b.id)
    }

  def sampleTracedTaskTick: Task[Option[CorrelationId]] = {
    for {
      _ <- tick
      i <- pure
    } yield i
  }

  def pure: Task[Option[CorrelationId]] = {
    Task.pure(CorrelationId.current)
  }

  val tick: Task[Unit] = Task.unsafeCreate { (ctx, cb) =>
    import java.util.concurrent.Executors
    val ec = Executors.newSingleThreadExecutor()

    ec.execute(new Runnable () {
      def run() = {
        try {
          cb(Success(()))
        } finally ec.shutdown()
      }
    })
  }

  System.setProperty("monix.environment.localContextPropagation", "true")

  test("should get CorrelarionId with flatmapped Task with async boundary") {
    // Works with the TracingScheduler given a Task.fromFuture
    import monix.execution.Scheduler.Implicits.traced

    val t1 = CorrelationId("0000").asCurrent {
      taskFlatMap.runAsync.map {
        case (x, v) =>
          (x, CorrelationId.current)
      }
    }
    val (_, cid1) = Await.result(t1, 10.seconds)
    assert(cid1.contains(CorrelationId("0000")))
  }

  test("should NOT get CorrelarionId with flatmapped Task with async boundary") {
    // Does not work without TracingScheduler
    // Meaning that when mapping an executed Task, the future will always
    // need the TracingScheduler to propagate the CorrelationId

    val t = CorrelationId("0000").asCurrent {
      import monix.execution.Scheduler.Implicits.global
      taskFlatMap.runAsync.map {
        case (x, v) =>
          (x, CorrelationId.current)
      }
    }
    val (_, cid) = Await.result(t, 10.seconds)
    assert(cid.isEmpty)
  }

  test("should get CorrelarionId with flatmapped Task with no async boundary") {

    val t1 = CorrelationId("1111").asCurrent {
      import monix.execution.Scheduler.Implicits.traced
      // Works with TracingScheduler and runAsync
      taskFlatMap.runAsync
    }
    val (_, cid1) = Await.result(t1, 10.seconds)
    assert(cid1.contains(CorrelationId("1111")))

    val t2 = CorrelationId("1111").asCurrent {
      import monix.execution.Scheduler.Implicits.global
      sampleTracedTaskTick.runAsync
    }
    val res = Await.result(t2, 10.seconds)
    assert(res contains CorrelationId("1111"))
  }

  test("should get CorrelationId with a composed Task executed inside current context") {
    import monix.execution.Scheduler.Implicits.global

    val t1 = CorrelationId("2222").asCurrent {
      val x = for {
        a <- Task.fromFuture(Future(1))
        b <- Task.now(1)
        c <- Task.deferFuture(Future(1))
        i <- composed
      } yield i.copy(value = i.value + a + b + c)
      x.runAsync
    }
    val res1 = Await.result(t1, 10.seconds)
    assert(res1.message contains "2222")

    val t2 = CorrelationId("2222").asCurrent {
      composed.runAsync
    }
    val res2 = Await.result(t2, 10.seconds)
    assert(res2.message contains "2222")
  }

  test("should NOT get CorrelationId with a composed Task executed outside current context") {
    import monix.execution.Scheduler.Implicits.traced

    val t1 = composed.runAsync
    t1.map(x => assert(x.message.isEmpty))

    val t2 = CorrelationId("2222").asCurrent(composed).runAsync
    t2.map(x => assert(x.message.isEmpty))

    val t3 = CorrelationId("2222").asCurrent {
      for {
        a <- Task.fromFuture(Future(1))
        b <- Task.now(1)
        c <- Task.deferFuture(Future(1))
      } yield Log(a + b + c, CorrelationId.current.fold("")(_.id))
    }.runAsync
    t3.map(x => assert(x.message.isEmpty))
  }

  test("should get CorrelationId Task.create with no async boundary") {
    import monix.execution.Scheduler.Implicits.global
    val create: Task[Option[CorrelationId]] = Task.create { (ctx, cb) =>

      cb(Success(CorrelationId.current))
      CancelableFuture.fromTry(Success(()))
    }
    val t = CorrelationId("3333").asCurrent {
      create.runAsync
    }
    val res = Await.result(t, 10.seconds)
    assert(res.contains(CorrelationId("3333")))
  }

  test("should get CorrelationId Task.mapBoth") {
    import monix.execution.Scheduler.Implicits.traced

    val t = CorrelationId("3333").asCurrent {
      taskMapBoth.runAsync
    }

    val res = Await.result(t, 10.seconds)
    assert(res.exists(x => x._1 == x._2))
  }
}
