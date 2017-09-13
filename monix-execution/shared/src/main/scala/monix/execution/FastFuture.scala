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

import cats.{CoflatMap, Eval, Monad, MonadError, StackSafeMonad}
import monix.execution.atomic.{AtomicAny, PaddingStrategy}
import monix.execution.internal.BlockingOps
import monix.execution.misc.NonFatal
import monix.execution.schedulers.TrampolineExecutionContext.immediate

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

sealed abstract class FastFuture[+A] extends Future[A] {
  override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
    BlockingOps.awaitForFuture(this, atMost)
    if (!this.isCompleted) throw new TimeoutException(s"Awaiting future, timed-out after $atMost")
    this
  }

  override def result(atMost: Duration)(implicit permit: CanAwait): A = {
    ready(atMost)
    value.get.get
  }

  final def transform[S](f: Try[A] => Try[S])(implicit ec: ExecutionContext): FastFuture[S] =
    FastFuture.async { cb =>
      onComplete { result =>
        val r2 = try f(result) catch { case NonFatal(e) => Failure(e) }
        cb(r2)
      }
    }

  final def transformWith[S](f: Try[A] => Future[S])(implicit ec: ExecutionContext): FastFuture[S] =
    FastFuture.async { cb =>
      onComplete { result =>
        val r2 = try f(result) catch { case NonFatal(e) => FastFuture.failed(e) }
        r2.onComplete(cb)(immediate)
      }
    }

  override final def transform[S](s: (A) => S, f: (Throwable) => Throwable)
    (implicit ec: ExecutionContext): FastFuture[S] =
    transform {
      case Success(a) => Success(s(a))
      case Failure(e) => Failure(f(e))
    }

  override final def failed: FastFuture[Throwable] = {
    implicit val ec = immediate
    FastFuture.async(cb => onComplete {
      case Failure(e) =>
        cb(Success(e))
      case _ =>
        cb(Failure(new NoSuchElementException("Future.failed")))
    })
  }


  override final def map[S](f: (A) => S)(implicit ec: ExecutionContext): FastFuture[S] =
    FastFuture.async { cb =>
      onComplete {
        case Success(a) =>
          cb(try Success(f(a)) catch { case NonFatal(e) => Failure(e) })
        case fail @ Failure(_) =>
          cb(fail.asInstanceOf[Failure[S]])
      }
    }

  override final def flatMap[S](f: (A) => Future[S])(implicit ec: ExecutionContext): FastFuture[S] =
    FastFuture.async { cb =>
      onComplete {
        case Success(a) =>
          val r2 = try f(a) catch { case NonFatal(e) => FastFuture.failed(e) }
          r2.onComplete(cb)(immediate)
        case fail @ Failure(_) =>
          cb(fail.asInstanceOf[Failure[S]])
      }
    }

  override final def filter(p: (A) => Boolean)(implicit ec: ExecutionContext): FastFuture[A] =
    transform {
      case Success(a) if !p(a) =>
        throw new NoSuchElementException("Future.filter predicate is not satisfied")
      case pass =>
        pass
    }

  override final def collect[S](pf: PartialFunction[A, S])(implicit ec: ExecutionContext): FastFuture[S] =
    transform {
      case Success(a) =>
        if (pf.isDefinedAt(a)) Success(pf(a)) else
          throw new NoSuchElementException("Future.collect partial function is not defined at: " + a)
      case fail @ Failure(_) =>
        fail.asInstanceOf[Failure[S]]
    }

  override final def recover[U >: A](pf: PartialFunction[Throwable, U])(implicit ec: ExecutionContext): FastFuture[U] =
    transform {
      case ref @ Success(_) => ref
      case Failure(e) =>
        if (!pf.isDefinedAt(e)) throw e
        Success(pf(e))
    }

  override final def recoverWith[U >: A](pf: PartialFunction[Throwable, Future[U]])(implicit ec: ExecutionContext): FastFuture[U] =
    transformWith {
      case Success(_) => this
      case Failure(e) =>
        if (!pf.isDefinedAt(e)) this
        else pf(e)
    }

  override final def zip[U](that: Future[U]): FastFuture[(A, U)] = {
    implicit val ec = immediate
    for (a <- this; b <- that) yield (a, b)
  }

  override final def fallbackTo[U >: A](that: Future[U]): FastFuture[U] = {
    implicit val ec = immediate
    transformWith {
      case Success(_) => this
      case Failure(_) => that
    }
  }

  override final def mapTo[S](implicit tag: ClassTag[S]): FastFuture[S] =
    FastFuture.fromFuture(super.mapTo[S])

  override final def andThen[U](pf: PartialFunction[Try[A], U])(implicit ec: ExecutionContext): FastFuture[A] =
    transformWith { r =>
      if (pf.isDefinedAt(r)) pf(r)
      this
    }
}


object FastFuture {
  def apply[A](f: => A)(implicit ec: ExecutionContext): FastFuture[A] =
    FastFuture.async { cb =>
      ec.execute(new Runnable {
        def run(): Unit =
          cb(try Success(f) catch { case NonFatal(e) => Failure(e) })
      })
    }

  def async[A](register: (Try[A] => Unit) => Unit)
    (implicit ec: ExecutionContext): FastFuture[A] = {

    val state = AtomicAny.withPadding[AnyRef](null, PaddingStrategy.LeftRight128)
    val future = new Async(state)(ec)
    future.start(register)
    future
  }

  def successful[A](value: A): FastFuture[A] =
    new Pure[A](Success(value))

  def pure[A](value: A): FastFuture[A] =
    successful(value)

  def failed[A](e: Throwable): FastFuture[A] =
    new Pure[A](Failure(e))

  def raiseError[A](e: Throwable): FastFuture[A] =
    failed(e)

  def fromTry[A](value: Try[A]): FastFuture[A] =
    new Pure[A](value)

  def fromFuture[A](f: Future[A]): FastFuture[A] =
    f match {
      case ref: FastFuture[_] =>
        ref.asInstanceOf[FastFuture[A]]
      case _ =>
        implicit val ec = immediate
        async[A](cb => f.onComplete(cb))
    }

  /** Returns the associated Cats type class instances for the
    * [[FastFuture]] data type.
    *
    * @param ec is the
    *        [[scala.concurrent.ExecutionContext ExecutionContext]]
    *        needed in order to create the needed type class instances,
    *        since future transformations rely on an `ExecutionContext`
    *        passed explicitly (by means of an implicit parameter)
    *        on each operation
    */
  implicit def catsInstances(implicit ec: ExecutionContext): CatsInstances =
    new CatsInstances()

  /** Implementation of Cats type classes for the
    * [[FastFuture]] data type.
    *
    * @param ec is the
    *        [[scala.concurrent.ExecutionContext ExecutionContext]]
    *        needed since future transformations rely on an
    *        `ExecutionContext` passed explicitly (by means of an
    *        implicit parameter) on each operation
    */
  final class CatsInstances(implicit ec: ExecutionContext)
    extends Monad[FastFuture]
      with StackSafeMonad[FastFuture]
      with CoflatMap[FastFuture]
      with MonadError[FastFuture, Throwable] {

    override def pure[A](x: A): FastFuture[A] =
      FastFuture.successful(x)
    override def map[A, B](fa: FastFuture[A])(f: A => B): FastFuture[B] =
      fa.map(f)
    override def flatMap[A, B](fa: FastFuture[A])(f: A => FastFuture[B]): FastFuture[B] =
      fa.flatMap(f)
    override def coflatMap[A, B](fa: FastFuture[A])(f: FastFuture[A] => B): FastFuture[B] =
      FastFuture(f(fa))
    override def handleErrorWith[A](fa: FastFuture[A])(f: Throwable => FastFuture[A]): FastFuture[A] =
      fa.recoverWith { case t => f(t) }
    override def raiseError[A](e: Throwable): FastFuture[Nothing] =
      FastFuture.failed(e)
    override def handleError[A](fea: FastFuture[A])(f: Throwable => A): FastFuture[A] =
      fea.recover { case t => f(t) }
    override def attempt[A](fa: FastFuture[A]): FastFuture[Either[Throwable, A]] =
      fa.transformWith(liftToEitherRef).asInstanceOf[FastFuture[Either[Throwable, A]]]
    override def recover[A](fa: FastFuture[A])(pf: PartialFunction[Throwable, A]): FastFuture[A] =
      fa.recover(pf)
    override def recoverWith[A](fa: FastFuture[A])(pf: PartialFunction[Throwable, FastFuture[A]]): FastFuture[A] =
      fa.recoverWith(pf)
    override def catchNonFatal[A](a: => A)(implicit ev: Throwable <:< Throwable): FastFuture[A] =
      FastFuture(a)
    override def catchNonFatalEval[A](a: Eval[A])(implicit ev: Throwable <:< Throwable): FastFuture[A] =
      FastFuture(a.value)

    override def adaptError[A](fa: FastFuture[A])(pf: PartialFunction[Throwable, Throwable]): FastFuture[A] =
      fa.transform {
        case Failure(e) if pf.isDefinedAt(e) => Failure(pf(e))
        case other => other
      }
  }

  private final class Pure[+A](pure: Try[A]) extends FastFuture[A] {
    override def onComplete[U](f: (Try[A]) => U)
      (implicit ec: ExecutionContext): Unit = {

      ec.execute(new Callback(f.asInstanceOf[Try[Any] => Any], pure))
    }

    override def isCompleted: Boolean = true
    override def value: Option[Try[A]] = Some(pure)
    override def ready(atMost: Duration)(implicit permit: CanAwait): Pure.this.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): A = pure.get
  }

  private final class Async[+A](
    private[this] val state: AtomicAny[AnyRef])
    (implicit ec: ExecutionContext)
    extends FastFuture[A] {

    // State:
    //  - null: initial state
    //  - Function1[Try[A], Unit]: one listener registered
    //  - List[Function1]: multiple listeners registered
    //  - Try[A]: completed value

    // Running locally at construction time
    def start[AA >: A](register: (Try[AA] => Unit) => Unit): Unit = {
      val completeFn = new OnComplete(state)(ec)
      register(completeFn.asInstanceOf[Try[AA] => Unit])
    }

    override def value: Option[Try[A]] =
      state.get match {
        case ref: Try[_] => Some(ref.asInstanceOf[Try[A]])
        case _ => None
      }

    override def isCompleted: Boolean =
      state.get match {
        case ref: Try[_] => true
        case _ => false
      }

    override def onComplete[U](f: (Try[A]) => U)(implicit ec: ExecutionContext): Unit = {
      // Tail-rec loop that keeps trying to compareAndSet until success
      @tailrec def loop(state: AtomicAny[AnyRef], fAny: Try[Any] => Any, listener: Listener = null)
        (implicit ec: ExecutionContext): Unit = {

        state.get match {
          case ref: Try[_] =>
            ec.execute(new Callback(fAny, ref))
          case null =>
            val l = if (listener != null) listener else Listener(fAny, ec)
            if (!state.compareAndSet(null, Listener(fAny, ec)))
              loop(state, fAny, l) // retry
          case ref: Listener =>
            val l = if (listener != null) listener else Listener(fAny, ec)
            if (!state.compareAndSet(ref, l :: ref :: Nil))
              loop(state, fAny, l) // retry
          case ref: List[_] =>
            val list = ref.asInstanceOf[List[Listener]]
            val l = if (listener != null) listener else Listener(fAny, ec)
            if (!state.compareAndSet(ref, l :: list))
              loop(state, fAny, l) // retry
        }
      }

      loop(state, f.asInstanceOf[Try[Any] => Any])
    }
  }

  private final case class Listener(
    call: Try[Any] => Any,
    context: ExecutionContext)

  private final class OnComplete(state: AtomicAny[AnyRef])
    (implicit ec: ExecutionContext)
    extends (Try[Any] => Any) {

    override def apply(result: Try[Any]): Unit = {
      val prev: AnyRef = state.getAndSet(result)

      if (prev != null) {
        if (prev.isInstanceOf[Listener]) {
          val f = prev.asInstanceOf[Listener]
          f.context.execute(new Callback(f.call, result))
        }
        else if (prev.isInstanceOf[List[_]]) {
          var cursor = prev.asInstanceOf[List[Listener]]
          while (cursor ne Nil) {
            val f = cursor.head
            cursor = cursor.tail
            f.context.execute(new Callback(f.call, result))
          }
        }
        else { // Try
          throw new IllegalStateException("Protocol violation, callback called multiple times!")
        }
      }
    }
  }

  private final class Callback(f: Try[Any] => Any, r: Try[Any])
    (implicit ec: ExecutionContext)
    extends Runnable {

    def run(): Unit =
      try f(r) catch { case NonFatal(e) => ec.reportFailure(e) }
  }

  // Reusable reference to use in `CatsInstances.attempt`
  private[this] final val liftToEitherRef: (Try[Any] => FastFuture[Either[Throwable, Any]]) =
    tryA => new Pure(Success(tryA match {
      case Success(a) => Right(a)
      case Failure(e) => Left(e)
    }))
}