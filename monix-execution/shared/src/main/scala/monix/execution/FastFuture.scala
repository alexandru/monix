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
import monix.execution.FastFuture.LightPromise
import monix.execution.atomic.{AtomicAny, PaddingStrategy}
import monix.execution.internal.BlockingOps
import monix.execution.misc.NonFatal
import monix.execution.schedulers.TrampolineExecutionContext.immediate
import monix.execution.schedulers.TrampolinedRunnable

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/** A [[scala.concurrent.Future]] re-implementation that makes use of
  * a couple of tricks to achieve better performance.
  *
  *  - uses Java 8 `getAndSet` platform intrinsics for completing the
  *    `Future`, which has better performance than `compareAndSet`,
  *    but is slightly less safe in case the protocol is violated
  *    (i.e. the completion callback is called multiple times), but
  *    that's a reasonable compromise
  *  - the [[Atomic]] reference used is
  *    [[PaddingStrategy cache line padded]] for better performance
  *    when multiple threads operate on it
  *  - the completion callbacks that are registered ''before'' the
  *    future completes will get executed on the same thread used
  *    to complete the `Future`, but only if they were registered
  *    with the same `ExecutionContext` and note that completion
  *    callbacks registered ''after'' the future completes will
  *    always fork
  *  - the [[scala.concurrent.Promise Promise]] abstraction comes
  *    with memory management overhead that we don't need, `FastFuture`
  *    exposing instead a simpler and more efficient
  *    [[monix.execution.FastFuture.LightPromise LightPromise]],
  *    along with a safer
  *    [[monix.execution.FastFuture.async FastFuture.async]] builder
  *
  * But in general the implementation tries its best to keep the same
  * semantics of Scala's `Future`. This means for example that when
  * calling [[FastFuture!.onComplete .onComplete]], you have guaranteed
  * async execution.
  *
  * See [[monix.execution.FastFuture.promise FastFuture.promise]]
  * and [[monix.execution.FastFuture.async FastFuture.async]] for
  * building `FastFuture` values out of asynchronous processes.
  *
  * See [[monix.execution.FastFuture.successful FastFuture.successful]]
  * and [[monix.execution.FastFuture.failed FastFuture.failed]] for
  * building already completed `FastFuture` values.
  */
sealed abstract class FastFuture[+A] extends Future[A] {
  // Abstract method, inherited
  def onComplete[U](f: (Try[A]) => U)(implicit ec: ExecutionContext): Unit
  // Abstract method, inherited
  def isCompleted: Boolean
  // Abstract method, inherited
  def value: Option[Try[A]]

  /** An [[onComplete]] version that only triggers light (trampolined)
    * asynchronous boundaries, to avoid the overhead of forking
    * logical threads in the `ExecutionContext`, used for optimizing
    * transformations.
    *
    * In normal usage, if you don't know what you're doing, use
    * the normal [[onComplete]].
    */
  def onCompleteLight[U](f: (Try[A]) => U)(implicit ec: ExecutionContext): Unit

  // Abstract method override, piggybacks a platform specific BlockingOps
  override def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
    BlockingOps.awaitForFuture(this, atMost)
    if (!this.isCompleted) throw new TimeoutException(s"Awaiting future, timed-out after $atMost")
    this
  }

  // Abstract method override, piggybacks a platform specific BlockingOps
  override def result(atMost: Duration)(implicit permit: CanAwait): A = {
    ready(atMost)
    value.get.get
  }

  // Performance-related override
  def transform[S](f: Try[A] => Try[S])(implicit ec: ExecutionContext): FastFuture[S] = {
    val promise = FastFuture.promise[S]
    onCompleteLight { result =>
      val r2 = try f(result) catch { case NonFatal(e) => Failure(e) }
      promise.complete(r2)
    }
    promise
  }

  // Performance-related override
  def transformWith[S](f: Try[A] => Future[S])(implicit ec: ExecutionContext): FastFuture[S] = {
    val promise = FastFuture.promise[S]
    onCompleteLight { result =>
      val r2 = try f(result) catch { case NonFatal(e) => FastFuture.failed(e) }
      r2.onComplete(promise.complete)(immediate)
    }
    promise
  }

  // Performance-related override
  override def transform[S](s: (A) => S, f: (Throwable) => Throwable)
    (implicit ec: ExecutionContext): FastFuture[S] =
    transform {
      case Success(a) => Success(s(a))
      case Failure(e) => Failure(f(e))
    }

  // Performance-related override
  override def failed: FastFuture[Throwable] = {
    implicit val ec = immediate
    val promise = new LightPromise[Throwable]()
    onCompleteLight {
      case Failure(e) =>
        promise.complete(Success(e))
      case _ =>
        promise.complete(Failure(new NoSuchElementException("Future.failed")))
    }
    promise
  }

  // Performance-related override
  override def map[S](f: (A) => S)(implicit ec: ExecutionContext): FastFuture[S] = {
    val promise = new LightPromise[S]()
    onCompleteLight {
      case Success(a) =>
        promise.complete(try Success(f(a)) catch { case NonFatal(e) => Failure(e) })
      case fail @ Failure(_) =>
        promise.complete(fail.asInstanceOf[Failure[S]])
    }
    promise
  }

  // Performance-related override
  override def flatMap[S](f: (A) => Future[S])(implicit ec: ExecutionContext): FastFuture[S] = {
    val promise = new LightPromise[S]()
    onCompleteLight {
      case Success(a) =>
        val r2 = try f(a) catch { case NonFatal(e) => FastFuture.failed(e) }
        r2.onComplete(promise.complete)(immediate)
      case fail @ Failure(_) =>
        promise.complete(fail.asInstanceOf[Failure[S]])
    }
    promise
  }

  // Performance-related override
  override def filter(p: (A) => Boolean)(implicit ec: ExecutionContext): FastFuture[A] =
    transform {
      case Success(a) if !p(a) =>
        throw new NoSuchElementException("Future.filter predicate is not satisfied")
      case pass =>
        pass
    }

  // Performance-related override
  override def collect[S](pf: PartialFunction[A, S])(implicit ec: ExecutionContext): FastFuture[S] =
    transform {
      case Success(a) =>
        if (pf.isDefinedAt(a)) Success(pf(a)) else
          throw new NoSuchElementException("Future.collect partial function is not defined at: " + a)
      case fail @ Failure(_) =>
        fail.asInstanceOf[Failure[S]]
    }

  // Performance-related override
  override def recover[U >: A](pf: PartialFunction[Throwable, U])(implicit ec: ExecutionContext): FastFuture[U] =
    transform {
      case ref @ Success(_) => ref
      case Failure(e) =>
        if (!pf.isDefinedAt(e)) throw e
        Success(pf(e))
    }

  // Performance-related override
  override def recoverWith[U >: A](pf: PartialFunction[Throwable, Future[U]])(implicit ec: ExecutionContext): FastFuture[U] =
    transformWith {
      case Success(_) => this
      case Failure(e) =>
        if (!pf.isDefinedAt(e)) this
        else pf(e)
    }

  // Performance-related override
  override def zip[U](that: Future[U]): FastFuture[(A, U)] = {
    implicit val ec = immediate
    for (a <- this; b <- that) yield (a, b)
  }

  // Performance-related override
  override def fallbackTo[U >: A](that: Future[U]): FastFuture[U] = {
    implicit val ec = immediate
    transformWith {
      case Success(_) => this
      case Failure(_) => that
    }
  }

  // Performance-related override
  override def mapTo[S](implicit tag: ClassTag[S]): FastFuture[S] =
    FastFuture.fromFuture(super.mapTo[S])

  // Performance-related override
  override def andThen[U](pf: PartialFunction[Try[A], U])(implicit ec: ExecutionContext): FastFuture[A] =
    transformWith { r =>
      if (pf.isDefinedAt(r)) pf(r)
      this
    }
}


object FastFuture {
  /** Executes the given expression asynchronously (managed by the
    * [[scala.concurrent.ExecutionContext ExecutionContext]] given
    * implicitly), building a [[FastFuture]] that will eventually
    * complete with its result.
    */
  def apply[A](f: => A)(implicit ec: ExecutionContext): FastFuture[A] = {
    val promise = new LightPromise[A]()
    ec.execute(new Runnable {
      def run(): Unit =
        promise.complete(try Success(f) catch { case NonFatal(e) => Failure(e) })
    })
    promise
  }

  /** Given a `register` function that executes an asynchronous process,
    * executes it and returns a [[FastFuture]] value that will
    * eventually complete with its result.
    *
    * For example this is what the [[FastFuture.apply]] implementation
    * looks like:
    *
    * {{{
    *   def execute[A](f: => A)(implicit ec: ExecutionContext): FastFuture[A] =
    *     FastFuture.async { complete =>
    *       ec.execute(new Runnable {
    *         def run(): Unit =
    *           complete(Try(f))
    *       })
    *     }
    * }}}
    *
    * This builder is the safer alternative to working with
    * Scala's `Promise`.
    *
    * Also see [[FastFuture.promise]] for the lower level alternative.
    *
    * @param register is a function that starts the asynchronous
    *        process, injected with a callback that needs to be
    *        called when the process finishes, either in success or
    *        failure
    *
    * @param ec is the `ExecutionContext` used for managing the async
    *        boundaries (needed for memory safety on completion)
    */
  def async[A](register: (Try[A] => Unit) => Unit)
    (implicit ec: ExecutionContext): FastFuture[A] = {

    val promise = new LightPromise[A]()
    // Light async boundary to protect against stack overflows!
    ec.execute(new TrampolinedRunnable {
      def run(): Unit =
        try register(promise.complete) catch {
          case NonFatal(e) =>
            ec.reportFailure(e)
        }
    })
    promise
  }

  /** Builds a [[FastFuture.LightPromise]] reference.
    *
    * This provides an alternative to Scala's
    * [[scala.concurrent.Promise Promise]].
    */
  def promise[A](implicit ec: ExecutionContext): LightPromise[A] =
    new LightPromise()(ec)

  /** Promotes a strict `value` to a [[FastFuture]] that's
    * already complete.
    *
    * @param value is the value that's going to be signaled in the
    *        `onComplete` callback.
    */
  def successful[A](value: A): FastFuture[A] =
    new Pure[A](Success(value))

  /** Promotes a strict `value` to a [[FastFuture]] that's
    * already complete.
    *
    * Alias for [[successful]].
    *
    * @param value is the value that's going to be signaled in the
    *        `onComplete` callback.
    */
  def pure[A](value: A): FastFuture[A] =
    successful(value)

  /** Promotes a strict `Throwable` to a [[FastFuture]] that's
    * already complete with a failure.
    *
    * @param e is the error that's going to be signaled in the
    *        `onComplete` callback.
    */
  def failed[A](e: Throwable): FastFuture[A] =
    new Pure[A](Failure(e))

  /** Promotes a strict `Throwable` to a [[FastFuture]] that's
    * already complete with a failure.
    *
    * Alias for [[failed]].
    *
    * @param e is the error that's going to be signaled in the
    *        `onComplete` callback.
    */
  def raiseError[A](e: Throwable): FastFuture[A] =
    failed(e)

  /** Promotes a strict `Try[A]` to a [[FastFuture]] that's
    * already complete.
    *
    * @param value is the `Try[A]` value that's going to be signaled
    *        in the `onComplete` callback.
    */
  def fromTry[A](value: Try[A]): FastFuture[A] =
    new Pure[A](value)

  /** Given any `Future` instance, converts it into a [[FastFuture]]
    * implementation by wrapping it, in case the given reference
    * does not implement `FastFuture` already.
    *
    * @param f is the `Future` reference to convert to a `FastFuture`
    */
  def fromFuture[A](f: Future[A]): FastFuture[A] =
    f match {
      case ref: FastFuture[_] =>
        ref.asInstanceOf[FastFuture[A]]
      case _ =>
        implicit val ec = immediate
        val promise = new LightPromise[A]()
        f.onComplete(promise.complete)
        promise
    }

  /** An already completed [[FastFuture]]. */
  final val unit: FastFuture[Unit] =
    successful(())

  /** Returns a [[FastFuture]] instance that will never complete. */
  final def never[A]: FastFuture[A] =
    Never

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

  /** Implementation for already completed [[FastFuture]] values. */
  private final class Pure[+A](pure: Try[A]) extends FastFuture[A] {
    override def onComplete[U](f: (Try[A]) => U)
      (implicit ec: ExecutionContext): Unit =
      ec.execute(new Callback(f.asInstanceOf[Try[Any] => Any], pure))

    override def onCompleteLight[U](f: (Try[A]) => U)
      (implicit ec: ExecutionContext): Unit =
      ec.execute(new TrampolinedCallback(f.asInstanceOf[Try[Any] => Any], pure))

    override def isCompleted: Boolean = true
    override def value: Option[Try[A]] = Some(pure)
    override def ready(atMost: Duration)(implicit permit: CanAwait): Pure.this.type = this
    override def result(atMost: Duration)(implicit permit: CanAwait): A = pure.get
  }

  /** Implementation for async [[FastFuture]] references that can
    * be completed, this being the direct equivalent of Scala's
    * [[scala.concurrent.Promise Promise]].
    *
    * Usage example:
    *
    * {{{
    *   // Building a reference
    *   val promise = FastFuture.promise[Int]
    *
    *   // ...
    *   promise.complete(Success(10))
    *
    *   // LightPromise is a FastFuture implementation
    *   val future: FastFuture[Int] = promise
    * }}}
    */
  final class LightPromise[A] private (
    private[this] val state: AtomicAny[AnyRef])
    (implicit ec: ExecutionContext)
    extends FastFuture[A] {

    /** Default constructor.
      *
      * @param ps is the [[PaddingStrategy]] to apply to the internal
      *        atomic reference that is used for keeping the current
      *        state
      *
      * @param ec is the `ExecutionContext` needed for managing
      *        internal asynchronous boundaries
      */
    def this(ps: PaddingStrategy = PaddingStrategy.LeftRight128)(implicit ec: ExecutionContext) =
      this(AtomicAny.withPadding[AnyRef](null, ps))

    // State:
    //  - null: initial state
    //  - Function1[Try[A], Unit]: one listener registered
    //  - List[Function1]: multiple listeners registered
    //  - Try[A]: completed value

    private[this] var completeRef: (Try[A]) => Unit = {
      new OnComplete(state)(ec).asInstanceOf[Try[A] => Unit]
    }

    def tryComplete(r: Try[A]): Unit = {
      val ref = completeRef
      if (ref ne spentComplete)
        try { ref(r) } catch { case _: IllegalStateException => }
    }

    def complete: (Try[A] => Unit) = {
      val ref = completeRef
      if (ref ne spentComplete) completeRef = spentComplete
      ref
    }

    def completeWith(f: Future[A]): Unit =
      f.onComplete(complete)(immediate)

    override def value: Option[Try[A]] =
      state.get match {
        case ref: Try[_] => Some(ref.asInstanceOf[Try[A]])
        case _ => None
      }

    override def isCompleted: Boolean =
      state.get match {
        case _: Try[_] => true
        case _ => false
      }

    private def _onComplete[U](f: (Try[A]) => U, mkCallback: (Try[Any] => Any, Try[Any], ExecutionContext) => Callback)
      (implicit ec: ExecutionContext): Unit = {

      // Tail-rec loop that keeps trying to compareAndSet until success
      @tailrec def loop(state: AtomicAny[AnyRef], fAny: Try[Any] => Any, listener: Listener = null)
        (implicit ec: ExecutionContext): Unit = {

        state.get match {
          case ref: Try[_] =>
            ec.execute(mkCallback(fAny, ref, ec))
          case null =>
            val l = if (listener != null) listener else Listener(fAny, mkCallback, ec)
            if (!state.compareAndSet(null, Listener(fAny, mkCallback, ec)))
              loop(state, fAny, l) // retry
          case ref: Listener =>
            val l = if (listener != null) listener else Listener(fAny, mkCallback, ec)
            if (!state.compareAndSet(ref, l :: ref :: Nil))
              loop(state, fAny, l) // retry
          case ref: List[_] =>
            val list = ref.asInstanceOf[List[Listener]]
            val l = if (listener != null) listener else Listener(fAny, mkCallback, ec)
            if (!state.compareAndSet(ref, l :: list))
              loop(state, fAny, l) // retry
        }
      }

      loop(state, f.asInstanceOf[Try[Any] => Any])
    }

    override def onComplete[U](f: (Try[A]) => U)(implicit ec: ExecutionContext): Unit =
      _onComplete(f, mkCallback)
    override def onCompleteLight[U](f: (Try[A]) => U)(implicit ec: ExecutionContext): Unit =
      _onComplete(f, mkTrampolinedCallback)
  }

  /** A [[FastFuture]] instance that will never complete. */
  private object Never extends FastFuture[Nothing] {
    def onComplete[U](f: (Try[Nothing]) => U)
      (implicit executor: ExecutionContext): Unit = ()
    def onCompleteLight[U](f: (Try[Nothing]) => U)
      (implicit ec: ExecutionContext): Unit = ()

    def isCompleted = false
    def value = None

    override def result(atMost: Duration)(implicit permit: CanAwait): Nothing =
      throw new TimeoutException("This FastFuture will never finish!")
    override def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
      throw new TimeoutException("This FastFuture will never finish!")

    def cancel(): Unit = ()

    // Overriding everything in order to avoid memory leaks

    override def transform[S](f: (Try[Nothing]) => Try[S])(implicit executor: ExecutionContext): FastFuture[S] =
      this
    override def transformWith[S](f: (Try[Nothing]) => Future[S])(implicit executor: ExecutionContext): FastFuture[S] =
      this
    override def failed: FastFuture[Throwable] =
      this
    override def transform[S](s: (Nothing) => S, f: (Throwable) => Throwable)(implicit executor: ExecutionContext): FastFuture[S] =
      this
    override def map[S](f: (Nothing) => S)(implicit executor: ExecutionContext): FastFuture[S] =
      this
    override def filter(p: (Nothing) => Boolean)(implicit executor: ExecutionContext): FastFuture[Nothing] =
      this
    override def collect[S](pf: PartialFunction[Nothing, S])(implicit executor: ExecutionContext): FastFuture[S] =
      this
    override def recover[U >: Nothing](pf: PartialFunction[Throwable, U])(implicit executor: ExecutionContext): FastFuture[U] =
      this
    override def recoverWith[U >: Nothing](pf: PartialFunction[Throwable, Future[U]])(implicit executor: ExecutionContext): FastFuture[U] =
      this
    override def zip[U](that: Future[U]): FastFuture[(Nothing, U)] =
      this
    override def fallbackTo[U >: Nothing](that: Future[U]): FastFuture[U] =
      this
    override def mapTo[S](implicit tag: ClassTag[S]): FastFuture[S] =
      this
    override def andThen[U](pf: PartialFunction[Try[Nothing], U])(implicit executor: ExecutionContext): FastFuture[Nothing] =
      this
    override def flatMap[S](f: (Nothing) => Future[S])(implicit executor: ExecutionContext): FastFuture[S] =
      this
  }

  private final case class Listener(
    call: Try[Any] => Any,
    mkCallback: MkCallback,
    context: ExecutionContext
  )

  private final class OnComplete(state: AtomicAny[AnyRef])
    (implicit ec: ExecutionContext)
    extends (Try[Any] => Any) {

    def execute(listener: Listener, result: Try[Any]): Unit = {
      val mk = listener.mkCallback
      listener.context.execute(mk(listener.call, result, ec))
    }

    override def apply(result: Try[Any]): Unit = {
      val prev: AnyRef = state.getAndSet(result)

      if (prev != null) {
        if (prev.isInstanceOf[Listener]) {
          val f = prev.asInstanceOf[Listener]
          execute(f, result)
        }
        else if (prev.isInstanceOf[List[_]]) {
          var cursor = prev.asInstanceOf[List[Listener]]
          while (cursor ne Nil) {
            val f = cursor.head
            cursor = cursor.tail
            execute(f, result)
          }
        }
        else { // Try
          throw new IllegalStateException("Protocol violation, callback called multiple times!")
        }
      }
    }
  }

  private class TrampolinedCallback(f: Try[Any] => Any, r: Try[Any])
    (implicit ec: ExecutionContext)
    extends Callback(f, r) with TrampolinedRunnable

  private class Callback(f: Try[Any] => Any, r: Try[Any])
    (implicit ec: ExecutionContext)
    extends Runnable {

    final def run(): Unit =
      try f(r) catch { case NonFatal(e) => ec.reportFailure(e) }
  }

  private type MkCallback =
    (Try[Any] => Any, Try[Any], ExecutionContext) => Callback

  private[this] final val mkTrampolinedCallback: MkCallback =
    (f, r, ec) => new TrampolinedCallback(f, r)(ec)
  private[this] final val mkCallback: MkCallback =
    (f, r, ec) => new Callback(f, r)(ec)

  // Reusable reference to use in `CatsInstances.attempt`
  private[this] final val liftToEitherRef: (Try[Any] => FastFuture[Either[Throwable, Any]]) =
    tryA => new Pure(Success(tryA match {
      case Success(a) => Right(a)
      case Failure(e) => Left(e)
    }))

  private[this] final val spentComplete: (Try[Any] => Nothing) =
    _ => throw new IllegalStateException("LightPromise was already completed!")
}