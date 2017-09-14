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

import java.util.concurrent.TimeoutException
import monix.execution.schedulers.TrampolineExecutionContext.immediate
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/** Utilities for Scala's standard `concurrent.Future`. */
object FutureUtils {
  /** Utility that returns a new Future that either completes with
    * the original Future's result or with a TimeoutException in case
    * the maximum wait time was exceeded.
    *
    * @param atMost specifies the maximum wait time until the future is
    *               terminated with a TimeoutException
    * @param s is the Scheduler, needed for completing our internal promise
    * @return a new future that will either complete with the result of our
    *         source or fail in case the timeout is reached.
    */
  def timeout[A](source: Future[A], atMost: FiniteDuration)(implicit s: Scheduler): Future[A] = {
    val err = new TimeoutException
    val promise = Promise[A]()
    val task = s.scheduleOnce(atMost.length, atMost.unit,
      new Runnable { def run() = promise.tryFailure(err) })

    source.onComplete { r =>
      // canceling task to prevent waisted CPU resources and memory leaks
      // if the task has been executed already, this has no effect
      task.cancel()
      promise.tryComplete(r)
    }

    promise.future
  }

  /** Utility that returns a new Future that either completes with
    * the original Future's result or after the timeout specified by
    * `atMost` it tries to complete with the given `fallback`.
    * Whatever `Future` finishes first after the timeout, will win.
    *
    * @param atMost specifies the maximum wait time until the future is
    *               terminated with a TimeoutException
    * @param fallback the fallback future that gets triggered after timeout
    * @param s is the Scheduler, needed for completing our internal promise
    * @return a new future that will either complete with the result of our
    *         source or with the fallback in case the timeout is reached
    */
  def timeoutTo[A](source: Future[A], atMost: FiniteDuration, fallback: => Future[A])
    (implicit s: Scheduler): Future[A] = {

    val promise = Promise[A]()
    val task = s.scheduleOnce(atMost.length, atMost.unit,
      new Runnable { def run() = promise.tryCompleteWith(fallback) })

    source.onComplete { r =>
      // canceling task to prevent waisted CPU resources and memory leaks
      // if the task has been executed already, this has no effect
      task.cancel()
      promise.tryComplete(r)
    }

    promise.future
  }

  /** Utility that lifts a `Future[A]` into a `Future[Try[A]]`, exposing
    * error explicitly.
    */
  def materialize[A](source: Future[A]): Future[Try[A]] = {
    if (source.isCompleted) {
      FastFuture.successful(source.value.get)
    }
    else {
      implicit val ec = immediate
      val promise = FastFuture.promise[Try[A]]
      source.onComplete(result => promise.complete(Success(result)))
      promise
    }
  }

  /** Given a mapping functions that operates on successful results
    * as well as errors, transforms the source by applying it.
    *
    * For Scala 2.11 provides a custom implementation, while deferring
    * to the `source`'s `transform` implementation for Scala 2.12.
    */
  def transform[A,B](source: Future[A], f: Try[A] => Try[B])(implicit ec: ExecutionContext): Future[B] =
    source.transform(f)(ec)

  /** Given a mapping functions that operates on successful results
    * as well as errors, transforms the source by applying it.
    *
    * For Scala 2.11 provides a custom implementation, while deferring
    * to the `source`'s `transformWith` implementation for Scala 2.12.
    */
  def transformWith[A,B](source: Future[A], f: Try[A] => Future[B])(implicit ec: ExecutionContext): Future[B] =
    source.transformWith(f)(ec)

  /** Utility that transforms a `Future[Try[A]]` into a `Future[A]`,
    * hiding errors, being the opposite of [[materialize]].
    */
  def dematerialize[A](source: Future[Try[A]]): Future[A] = {
    if (source.isCompleted)
      source.value.get match {
        case Failure(error) => FastFuture.failed(error)
        case Success(value) => value match {
          case Success(success) => FastFuture.successful(success)
          case Failure(error) => FastFuture.failed(error)
        }
      }
    else {
      implicit val ec = immediate
      val promise = FastFuture.promise[A]
      source.onComplete {
        case Failure(error) => promise.complete(Failure(error))
        case Success(result) => promise.complete(result)
      }
      promise
    }
  }

  /** Creates a future that completes with the specified `result`, but only
    * after the specified `delay`.
    */
  def delayedResult[A](delay: FiniteDuration)(result: => A)(implicit s: Scheduler): Future[A] = {
    val promise = FastFuture.promise[A]
    s.scheduleOnce(delay.length, delay.unit, new Runnable { def run() = promise.complete(Try(result)) })
    promise
  }

  /** Provides extension methods for `Future`. */
  object extensions {
    /** Provides utility methods added on Scala's `concurrent.Future` */
    implicit class FutureExtensions[A](val source: Future[A]) extends AnyVal {
      /** [[FutureUtils.timeout]] exposed as an extension method. */
      def timeout(atMost: FiniteDuration)(implicit s: Scheduler): Future[A] =
        FutureUtils.timeout(source, atMost)

      /** [[FutureUtils.timeoutTo]] exposed as an extension method. */
      def timeoutTo[U >: A](atMost: FiniteDuration, fallback: => Future[U])
        (implicit s: Scheduler): Future[U] =
        FutureUtils.timeoutTo(source, atMost, fallback)

      /** [[FutureUtils.materialize]] exposed as an extension method. */
      def materialize(implicit ec: ExecutionContext): Future[Try[A]] =
        FutureUtils.materialize(source)

      /** [[FutureUtils.dematerialize]] exposed as an extension method. */
      def dematerialize[U](implicit ev: A <:< Try[U], ec: ExecutionContext): Future[U] =
        FutureUtils.dematerialize(source.asInstanceOf[Future[Try[U]]])
    }

    /** Provides utility methods for Scala's `concurrent.Future` companion object. */
    implicit class FutureCompanionExtensions(val f: Future.type) extends AnyVal {
      /** [[FutureUtils.delayedResult]] exposed as an extension method. */
      def delayedResult[A](delay: FiniteDuration)(result: => A)(implicit s: Scheduler): Future[A] =
        FutureUtils.delayedResult(delay)(result)
    }
  }
}
