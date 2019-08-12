/*
 * Copyright (c) 2014-2019 by The Monix Project Developers.
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

package monix.execution.misc

import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import monix.execution.{CancelableFuture, FutureUtils}
import monix.execution.schedulers.TrampolineExecutionContext
import scala.concurrent.Future

/**
  * Type class describing how [[Local]] binding works for specific data types.
  *
  * This is needed because asynchronous data types, like `Future`,
  * that can be waited on, should also clear the modified context
  * after completion.
  *
  * NOTE: this type class does not work for data types that suspend the
  * execution, like `Coeval` or `Task`, because [[Local]] is meant to
  * be used in a side effectful way. Instances of this type class
  * can't be implemented for data types like `Task`, as a technical
  * limitation, because `Task` would also need a suspended `Context`
  * evaluation in `bindContext`.
  */
trait CanBindLocals[R] {
  /** See [[Local.bind[A](ctx* Local.bind]]. */
  def bindContext(ctx: Local.Context)(f: => R): R

  /** See [[Local.bind[A](value* Local.bind]]. */
  def bindKey[A](local: Local[A], value: Option[A])(f: => R): R =
    bindContext(Local.getContext().bind(local.key, value))(f)

  /** See [[Local.isolate]]. */
  def isolate(f: => R): R =
    bindContext(Local.getContext().isolate())(f)
}

object CanBindLocals extends CanIsolateInstancesLevel1 {
  def apply[R](implicit R: CanBindLocals[R]): CanBindLocals[R] = R
}

private[misc] abstract class CanIsolateInstancesLevel1 extends CanIsolateInstancesLevel0 {
  /**
    * Instance for `monix.execution.CancelableFuture`.
    */
  implicit def cancelableFuture[R]: CanBindLocals[CancelableFuture[R]] =
    FutureInstance.asInstanceOf[CanBindLocals[CancelableFuture[R]]]

  /**
    * Instance for `java.util.concurrent.CompletableFuture`.
    */
  implicit def completableFuture[R]: CanBindLocals[CompletableFuture[R]] =
    CompletableFutureInstance.asInstanceOf[CanBindLocals[CompletableFuture[R]]]

  /**
    * Instance for `scala.concurrent.Future`.
    */
  implicit def future[R]: CanBindLocals[Future[R]] =
    FutureInstance.asInstanceOf[CanBindLocals[Future[R]]]
}

private[misc] abstract class CanIsolateInstancesLevel0 {
  /**
    * Instance for `scala.Unit`.
    */
  implicit def synchronous[R]: CanBindLocals[R] =
    SynchronousInstance.asInstanceOf[CanBindLocals[R]]

  /** Implementation for [[CanBindLocals.synchronous]]. */
  protected object SynchronousInstance extends CanBindLocals[Any] {
    override def bindContext(ctx: Local.Context)(f: => Any): Any = {
      val prev = Local.getContext()
      Local.setContext(ctx)
      try f
      finally Local.setContext(prev)
    }
  }

  /** Implementation for [[CanBindLocals.future]]. */
  protected object FutureInstance extends CanBindLocals[Future[Any]] {
    override def bindContext(ctx: Local.Context)(f: => Future[Any]): Future[Any] = {
      val prev = Local.getContext()
      Local.setContext(ctx)

      try {
        FutureUtils
          .transform[Any, Any](f, result => {
            Local.setContext(prev)
            result
          })(TrampolineExecutionContext.immediate)
      } finally {
        Local.setContext(prev)
      }
    }

    override def bindKey[A](local: Local[A], value: Option[A])(f: => Future[Any]): Future[Any] =
      super.bindKey(local, value)(f)

    override def isolate(f: => Future[Any]): Future[Any] = super.isolate(f)
  }

  /** Implementation for [[CanBindLocals.completableFuture]]. */
  protected object CompletableFutureInstance extends CanBindLocals[CompletableFuture[Any]] {
    override def bindContext(ctx: Local.Context)(f: => CompletableFuture[Any]): CompletableFuture[Any] = {
      val prev = Local.getContext()
      Local.setContext(ctx)

      try {
        f.handleAsync(
          new BiFunction[Any, Throwable, Any] {
            def apply(r: Any, error: Throwable): Any = {
              Local.setContext(prev)
              if (error != null) throw error
              else r
            }
          },
          TrampolineExecutionContext.immediate
        )
      } finally {
        Local.setContext(prev)
      }
    }
  }
}
