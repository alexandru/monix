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

package monix.eval.internal

import monix.eval.Task.{Async, Context, Error, Eval, FlatMap, FrameIndex, Map, MemoizeSuspend, Now, Suspend, fromTry}
import monix.eval.Task.{NOW_ID, ASYNC_ID, ERROR_ID, EVAL_ID, FRAME_ID, MEMOIZE_ID, SUSPEND_ID}
import monix.eval.{Callback, Task}
import monix.execution.atomic.AtomicAny
import monix.execution.cancelables.StackedCancelable
import monix.execution.internal.collection.ArrayStack
import monix.execution.misc.{Local, NonFatal}
import monix.execution.schedulers.TrampolinedRunnable
import monix.execution.{Cancelable, CancelableFuture, ExecutionModel, Scheduler}
import scala.concurrent.Promise
import scala.util.{Failure, Success, Try}

private[eval] object TaskRunLoop {
  private type Current = Task[Any]
  private type Bind = Task.Frame[Any, Any]
  private type CallStack = ArrayStack[Bind]

  // We always start from 1
  final def frameStart(em: ExecutionModel): FrameIndex =
    em.nextFrameIndex(0)

  /** Internal utility, for forcing an asynchronous boundary in the
    * trampoline loop.
    */
  def restartAsync[A](
    source: Task[A],
    context: Context,
    cb: Callback[A],
    rcb: RestartCallback,
    bindCurrent: Bind,
    bindRest: CallStack): Unit = {

    val savedLocals =
      if (context.options.localContextPropagation) Local.getContext()
      else null

    if (!context.shouldCancel) {
      context.scheduler.executeAsync { () =>
        // Resetting the frameRef, as a real asynchronous boundary happened
        context.frameRef.reset()
        // Transporting the current context if localContextPropagation == true.
        Local.bind(savedLocals) {
          // Using frameIndex = 1 to ensure at least one cycle gets executed
          startFull(source, context, cb, rcb, bindCurrent, bindRest, 1)
        }
      }
    }
  }

  /** Starts or resumes evaluation of the run-loop from where it left
    * off. This is the complete, callback and context based run-loop.
    *
    * Used for continuing a run-loop after an async boundary
    * happens from [[startFuture]] and [[startLight]].
    *
    * The `rcb`, `bFirst` and `bRest` parameters are nullable.
    */
  def startFull[A](
    source: Task[A],
    context: Context,
    cb: Callback[A],
    rcb: RestartCallback /* | Null */,
    bFirstInit: Bind /* | Null */,
    bRestInit: CallStack /* | Null */,
    frameIndexStart: FrameIndex): Unit = {

    def executeOnFinish(
      context: Context,
      cb: Callback[Any],
      rcb: RestartCallback,
      bFirst: Bind,
      bRest: CallStack,
      register: (Context, Callback[Any]) => Unit,
      nextFrame: FrameIndex): Unit = {

      if (!context.shouldCancel) {
        // We are going to resume the frame index from where we left,
        // but only if no real asynchronous execution happened. So in order
        // to detect asynchronous execution, we are reading a thread-local
        // variable that's going to be reset in case of a thread jump.
        // Obviously this doesn't work for Javascript or for single-threaded
        // thread-pools, but that's OK, as it only means that in such instances
        // we can experience more async boundaries and everything is fine for
        // as long as the implementation of `Async` tasks are triggering
        // a `frameRef.reset` on async boundaries.
        context.frameRef := nextFrame

        // rcb reference might be null, so initializing
        val rcbRef = if (rcb != null) rcb else new RestartCallback(context, cb)
        rcbRef.prepare(bFirst, bRest)
        register(context, rcbRef)
      }
    }

    val cba = cb.asInstanceOf[Callback[Any]]
    val em = context.executionModel
    var current = source.asInstanceOf[Task[Any]]
    var bFirst = bFirstInit
    val bRest = if (bRestInit ne null) bRestInit else new ArrayStack[Bind]()
    var frameIndex = frameIndexStart
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    while (frameIndex != 0) {
      current.id match {
        case FRAME_ID =>
          if (bFirst ne null) bRest.push(bFirst)
          bFirst = current.asInstanceOf[Bind]
          current = bFirst.source

        case NOW_ID =>
          val ref = current.asInstanceOf[Now[AnyRef]]
          unboxed = ref.value
          hasUnboxed = true

        case EVAL_ID =>
          val ref = current.asInstanceOf[Eval[AnyRef]]
          // Indirection to avoid ObjectRef & BoxedUnit
          val value = try {
            val ret = ref.thunk()
            current = null
            hasUnboxed = true
            ret
          } catch {
            case NonFatal(e) =>
              current = new Error(e)
              null
          }
          unboxed = value

        case SUSPEND_ID =>
          val ref = current.asInstanceOf[Suspend[AnyRef]]
          // Indirection to avoid ObjectRef
          val next = try ref.thunk() catch { case NonFatal(e) => new Error(e) }
          current = next

        case ERROR_ID =>
          val ref = current.asInstanceOf[Error[AnyRef]]
          val bind = findErrorHandler(bFirst, bRest)
          // Not pattern matching in order to avoid usage of "BoxedUnit"
          if (bind ne null) {
            bFirst = null
            frameIndex = em.nextFrameIndex(frameIndex)
            // Indirection to avoid ObjectRef
            val next = try bind(ref.error) catch { case NonFatal(e) => new Error(e) }
            current = next
          } else {
            cb.onError(ref.error)
            return
          }

        case ASYNC_ID =>
          val ref = current.asInstanceOf[Async[AnyRef]]
          executeOnFinish(context, cba, rcb, bFirst, bRest, ref.register, frameIndex)
          return

        case MEMOIZE_ID =>
          val ref = current.asInstanceOf[MemoizeSuspend[AnyRef]]
          // Usage as expression in order to avoid "BoxedUnit"
          current = ref.value match {
            case Some(materialized) =>
              materialized match {
                case Success(any) =>
                  unboxed = any.asInstanceOf[AnyRef]
                  hasUnboxed = true
                  null
                case Failure(error) =>
                  new Error(error)
              }
            case None =>
              val anyRef = ref.asInstanceOf[MemoizeSuspend[Any]]
              val isSuccess = startMemoization(anyRef, context, cba, rcb, bFirst, bRest, frameIndex)
              // If not isSuccess, a race condition happened and we must retry!
              if (isSuccess) return else current
          }
      }

      if (hasUnboxed) {
        val bind = popNextBind(bFirst, bRest)
        // Not pattern matching in order to avoid usage of "BoxedUnit"
        if (bind ne null) {
          // Indirection to avoid ObjectRef
          val next = try bind(unboxed) catch { case NonFatal(e) => new Error(e) }
          current = next
          // No longer in unboxed state
          hasUnboxed = false
          bFirst = null
          frameIndex = em.nextFrameIndex(frameIndex)
          unboxed = null // GC purposes
        } else {
          cba.onSuccess(unboxed)
          return
        }
      }
    }
    // frameIndex == 0, force async boundary
    restartAsync(current, context, cba, rcb, bFirst, bRest)
  }

  /** A run-loop that attempts to evaluate a `Task` without
    * initializing a `Task.Context`, falling back to
    * [[startFull]] when the first `Async` boundary is hit.
    *
    * Function gets invoked by `Task.runAsync(cb: Callback)`.
    */
  def startLight[A](
    source: Task[A],
    scheduler: Scheduler,
    opts: Task.Options,
    cb: Callback[A]): Cancelable = {

    var current = source.asInstanceOf[Task[Any]]
    var bFirst: Bind = null
    val bRest: CallStack = new ArrayStack[Bind]()
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null
    // Keeps track of the current frame, used for forced async boundaries
    val em = scheduler.executionModel
    var currentIndex = frameStart(em)

    while (currentIndex != 0) {
      current.id match {
        case FRAME_ID =>
          if (bFirst ne null) bRest.push(bFirst)
          bFirst = current.asInstanceOf[Bind]
          current = bFirst.source

        case NOW_ID =>
          val ref = current.asInstanceOf[Now[AnyRef]]
          unboxed = ref.value
          hasUnboxed = true

        case EVAL_ID =>
          val ref = current.asInstanceOf[Eval[AnyRef]]
          // Indirection to avoid ObjectRef & BoxedUnit
          val value = try {
            val ret = ref.thunk()
            current = null
            hasUnboxed = true
            ret
          } catch {
            case NonFatal(e) =>
              current = new Error(e)
              null
          }
          unboxed = value

        case SUSPEND_ID =>
          val ref = current.asInstanceOf[Suspend[AnyRef]]
          // Indirection to avoid ObjectRef
          val next = try ref.thunk() catch { case NonFatal(e) => new Error(e) }
          current = next

        case ERROR_ID =>
          val ref = current.asInstanceOf[Error[AnyRef]]
          val bind = findErrorHandler(bFirst, bRest)
          // Not pattern matching in order to avoid usage of "BoxedUnit"
          if (bind ne null) {
            bFirst = null
            currentIndex = em.nextFrameIndex(currentIndex)
            // Indirection to avoid ObjectRef
            val next = try bind(ref.error) catch { case NonFatal(e) => new Error(e) }
            current = next
          } else {
            cb.onError(ref.error)
            return Cancelable.empty
          }

        case ASYNC_ID =>
          return goAsync4LightCB(
            current, scheduler, opts,
            cb.asInstanceOf[Callback[Any]],
            bFirst, bRest, currentIndex,
            forceAsync = false)

        case MEMOIZE_ID =>
          val ref = current.asInstanceOf[MemoizeSuspend[AnyRef]]
          // Usage as expression in order to avoid "BoxedUnit"
          current = ref.value match {
            case Some(materialized) =>
              materialized match {
                case Success(any) =>
                  unboxed = any.asInstanceOf[AnyRef]
                  hasUnboxed = true
                  null
                case Failure(error) =>
                  new Error(error)
              }
            case None =>
              // Not memoized yet, go async and do full loop
              return goAsync4LightCB(
                current, scheduler, opts,
                cb.asInstanceOf[Callback[Any]],
                bFirst, bRest, currentIndex,
                forceAsync = false)
          }
      }

      if (hasUnboxed) {
        val bind = popNextBind(bFirst, bRest)
        // Not pattern matching in order to avoid usage of "BoxedUnit"
        if (bind ne null) {
          // Indirection to avoid ObjectRef
          val next = try bind(unboxed) catch { case NonFatal(e) => new Error(e) }
          current = next
          // No longer in unboxed state
          hasUnboxed = false
          bFirst = null
          currentIndex = em.nextFrameIndex(currentIndex)
          unboxed = null // GC purposes
        } else {
          cb.onSuccess(unboxed.asInstanceOf[A])
          return Cancelable.empty
        }
      }
    }
    // frameIndex == 0, force async boundary
    goAsync4LightCB(
      current, scheduler, opts,
      cb.asInstanceOf[Callback[Any]],
      bFirst, bRest, currentIndex,
      forceAsync = true)
  }

  /** A run-loop that attempts to complete a `CancelableFuture`
    * synchronously falling back to [[startFull]] and actual
    * asynchronous execution in case of an asynchronous boundary.
    *
    * Function gets invoked by `Task.runAsync(implicit s: Scheduler)`.
    */
  def startFuture[A](source: Task[A], scheduler: Scheduler, opts: Task.Options): CancelableFuture[A] = {
    var current = source.asInstanceOf[Task[Any]]
    var bFirst: Bind = null
    val bRest: CallStack = new ArrayStack[Bind]()
    // Values from Now, Always and Once are unboxed in this var, for code reuse
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null
    // Keeps track of the current frame, used for forced async boundaries
    val em = scheduler.executionModel
    var currentIndex = frameStart(em)

    while (currentIndex != 0) {
      current.id match {
        case FRAME_ID =>
          if (bFirst ne null) bRest.push(bFirst)
          bFirst = current.asInstanceOf[Bind]
          current = bFirst.source

        case NOW_ID =>
          val ref = current.asInstanceOf[Now[AnyRef]]
          unboxed = ref.value
          hasUnboxed = true

        case EVAL_ID =>
          val ref = current.asInstanceOf[Eval[AnyRef]]
          // Indirection to avoid ObjectRef & BoxedUnit
          val value = try {
            val ret = ref.thunk()
            current = null
            hasUnboxed = true
            ret
          } catch {
            case NonFatal(e) =>
              current = new Error(e)
              null
          }
          unboxed = value

        case SUSPEND_ID =>
          val ref = current.asInstanceOf[Suspend[AnyRef]]
          // Indirection to avoid ObjectRef
          val next = try ref.thunk() catch { case NonFatal(e) => new Error(e) }
          current = next

        case ERROR_ID =>
          val ref = current.asInstanceOf[Error[AnyRef]]
          val bind = findErrorHandler(bFirst, bRest)
          // Not pattern matching in order to avoid usage of "BoxedUnit"
          if (bind ne null) {
            bFirst = null
            currentIndex = em.nextFrameIndex(currentIndex)
            // Indirection to avoid ObjectRef
            val next = try bind(ref.error) catch { case NonFatal(e) => new Error(e) }
            current = next
          } else {
            return CancelableFuture.failed(ref.error)
          }

        case ASYNC_ID =>
          return goAsync4Future(current, scheduler, opts, bFirst, bRest, currentIndex, forceAsync = false)

        case MEMOIZE_ID =>
          val ref = current.asInstanceOf[MemoizeSuspend[AnyRef]]
          // Usage as expression in order to avoid "BoxedUnit"
          current = ref.value match {
            case Some(materialized) =>
              materialized match {
                case Success(any) =>
                  unboxed = any.asInstanceOf[AnyRef]
                  hasUnboxed = true
                  null
                case Failure(error) =>
                  new Error(error)
              }
            case None =>
              // Not memoized yet, go async and do full loop
              return goAsync4Future(current, scheduler, opts, bFirst, bRest, currentIndex, forceAsync = false)
          }
      }

      if (hasUnboxed) {
        val bind = popNextBind(bFirst, bRest)
        // Not pattern matching in order to avoid usage of "BoxedUnit"
        if (bind ne null) {
          // Indirection to avoid ObjectRef
          val next = try bind(unboxed) catch { case NonFatal(e) => new Error(e) }
          current = next
          // No longer in unboxed state
          hasUnboxed = false
          bFirst = null
          currentIndex = em.nextFrameIndex(currentIndex)
          unboxed = null // GC purposes
        } else {
          return CancelableFuture.successful(unboxed.asInstanceOf[A])
        }
      }
    }
    // frameIndex == 0, force async boundary
    goAsync4Future(current, scheduler, opts, bFirst, bRest, currentIndex, forceAsync = true)
  }

  /** Called when we hit the first async boundary in
    * [[startLight]].
    */
  private def goAsync4LightCB(
    source: Current,
    scheduler: Scheduler,
    opts: Task.Options,
    cb: Callback[Any],
    bindCurrent: Bind,
    bindRest: CallStack,
    nextFrame: FrameIndex,
    forceAsync: Boolean): Cancelable = {

    val context = Context(scheduler, opts)
    if (forceAsync)
      restartAsync(source, context, cb, null, bindCurrent, bindRest)
    else
      startFull(source, context, cb, null, bindCurrent, bindRest, nextFrame)

    context.connection
  }

  /** Called when we hit the first async boundary in [[startFuture]]. */
  private def goAsync4Future[A](
    source: Current,
    scheduler: Scheduler,
    opts: Task.Options,
    bindCurrent: Bind,
    bindRest: CallStack,
    nextFrame: FrameIndex,
    forceAsync: Boolean): CancelableFuture[A] = {

    val p = Promise[A]()
    val cb = Callback.fromPromise(p)
    val fa = source.asInstanceOf[Task[A]]
    val context = Context(scheduler, opts)
    if (forceAsync)
      restartAsync(fa, context, cb, null, bindCurrent, bindRest)
    else
      startFull(fa, context, cb, null, bindCurrent, bindRest, nextFrame)

    CancelableFuture(p.future, context.connection)
  }

  /** Starts the execution and memoization of a `Task.MemoizeSuspend` state.
    *
    * The `rcb`, `bFirst` and `bRest` parameters are nullable.
    */
  private def startMemoization[A](
    self: MemoizeSuspend[A],
    context: Context,
    cb: Callback[A],
    rcb: RestartCallback /* | Null */,
    bindCurrent: Bind /* | Null */,
    bindRest: CallStack /* | Null */,
    nextFrame: FrameIndex): Boolean = {

    // Internal function that stores the generated
    // value in our internal atomic reference
    def cacheValue(state: AtomicAny[AnyRef], value: Try[A]): Unit = {
      // Should we cache everything, error results as well,
      // or only successful results?
      if (self.cacheErrors || value.isSuccess) {
        self.thunk = null // GC purposes
        state.getAndSet(value) match {
          case (p: Promise[_], _) =>
            p.asInstanceOf[Promise[A]].complete(value)
            return // avoids BoxedUnit
          case _ =>
            return // avoids BoxedUnit
        }
      } else {
        // Error happened and we are not caching errors!
        val current = state.get
        // Resetting the state to `null` will trigger the
        // execution again on next `runAsync`
        if (state.compareAndSet(current, null))
          current match {
            case (p: Promise[_], _) =>
              p.asInstanceOf[Promise[A]].complete(value)
              return // avoids BoxedUnit
            case _ =>
              return // avoids BoxedUnit
          }
        else
          cacheValue(state, value) // retry
      }
    }

    implicit val ec: Scheduler = context.scheduler
    self.state.get match {
      case null =>
        val p = Promise[A]()

        if (!self.state.compareAndSet(null, (p, context.connection))) {
          // $COVERAGE-OFF$
          startMemoization(self, context, cb, rcb, bindCurrent, bindRest, nextFrame) // retry
          // $COVERAGE-ON$
        } else {
          val underlying = try self.thunk() catch { case NonFatal(ex) => Error(ex) }

          val callback = new Callback[A] {
            def onError(ex: Throwable): Unit = {
              cacheValue(self.state, Failure(ex))
              restartAsync(Error(ex), context, cb, rcb, bindCurrent, bindRest)
            }
            def onSuccess(value: A): Unit = {
              cacheValue(self.state, Success(value))
              restartAsync(Now(value), context, cb, rcb, bindCurrent, bindRest)
            }
          }

          // Light async boundary to prevent stack-overflows!
          ec.execute(new TrampolinedRunnable {
            def run(): Unit = {
              startFull(underlying, context, callback, null, null, null, nextFrame)
            }
          })
          true
        }

      case (p: Promise[_], mainCancelable: StackedCancelable) =>
        // execution is pending completion
        context.connection push mainCancelable
        p.asInstanceOf[Promise[A]].future.onComplete { r =>
          context.connection.pop()
          context.frameRef.reset()
          startFull(fromTry(r), context, cb, rcb, bindCurrent, bindRest, 1)
        }
        true

      case _: Try[_] =>
        // Race condition happened
        // $COVERAGE-OFF$
        false
        // $COVERAGE-ON$
    }
  }

  def findErrorHandler(bFirst: Bind, bRest: CallStack): Throwable => Task[Any] = {
    var cursor = bFirst
    do {
      cursor = cursor match {
        case FlatMap(_, _, g) if g ne null =>
          return g
        case _ =>
          bRest.pop()
      }
    } while (cursor ne null)
    // None found
    null
  }

  def popNextBind(bFirst: Bind, bRest: CallStack): Any => Task[Any] = {
    var cursor = bFirst
    do {
      cursor = cursor match {
        case FlatMap(_, f, _) if f ne null =>
          return f
        case ref @ Map(_, _, _) =>
          return ref
        case _ =>
          bRest.pop()
      }
    } while (cursor ne null)
    // None found
    null
  }

  /** Callback used in the run-loop implementation that restarts the
    * loop; as an optimization for reusing the same instance in order
    * to avoid extraneous memory allocations.
    */
  final class RestartCallback(
    private[this] val _context: Context,
    private[this] val _callback: Callback[Any]) extends Callback[Any] {

    private[this] val runLoopIndex = _context.frameRef
    private[this] val withLocal = _context.options.localContextPropagation
    // Variables that get mutated in prepare/call cycle
    private[this] var canCall = false
    private[this] var bFirst: Bind = _
    private[this] var bRest: CallStack = _
    private[this] var savedLocals: Local.Context = _

    def prepare(bindCurrent: Bind, bindRest: CallStack): Unit = {
      canCall = true
      this.bFirst = bindCurrent
      this.bRest = bindRest
      if (withLocal)
        savedLocals = Local.getContext()
    }

    def onSuccess(value: Any): Unit =
      if (canCall) {
        canCall = false
        Local.bind(savedLocals) {
          startFull(Now(value), _context, _callback, this, bFirst, bRest, runLoopIndex())
        }
      }

    def onError(ex: Throwable): Unit = {
      if (canCall) {
        canCall = false
        Local.bind(savedLocals) {
          startFull(Error(ex), _context, _callback, this, bFirst, bRest, runLoopIndex())
        }
      } else {
        // $COVERAGE-OFF$
        _context.scheduler.reportFailure(ex)
        // $COVERAGE-ON$
      }
    }

    override def toString: String = {
      // $COVERAGE-OFF$
      s"RestartCallback$$${System.identityHashCode(this)}"
      // $COVERAGE-ON$
    }
  }
}
