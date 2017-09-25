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

package monix.tail.internal

import cats.Applicative
import cats.effect.Async
import monix.eval.Callback
import monix.execution.atomic.{Atomic, AtomicAny}
import monix.execution.atomic.PaddingStrategy.LeftRight128
import monix.execution.schedulers.TrampolineExecutionContext.immediate
import monix.execution.rstreams.SingleAssignmentSubscription
import monix.tail.Iterant
import monix.tail.batches.Batch
import org.jctools.queues.SpscArrayQueue
import org.reactivestreams.{Publisher, Subscriber, Subscription}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private[tail] object PublisherToIterant {
  def apply[F[_], A](p: Publisher[A], batchSize: Int)
    (implicit F: Async[F], ec: ExecutionContext): Iterant[F, A] = {

    Iterant.suspend[F, A](F.suspend {
      val subscriber = new IterantSubscriber[F, A](batchSize)
      p.subscribe(subscriber)
      subscriber.consumerLoop()
    })
  }

  private class IterantSubscriber[F[_], A](batchSize: Int)
    (implicit F: Async[F], ec: ExecutionContext)
    extends Subscriber[A] {

    private[this] val queue = new SpscArrayQueue[AnyRef](batchSize + 1)
    private[this] val subscription = SingleAssignmentSubscription()
    private[this] val itemsPromise =
      Atomic.withPadding(null : Promise[Unit], LeftRight128)

    // MUST BE modified only by the consumer loop
    private[this] var requested = 0
    // MUST BE modified only in `onComplete` or `onError`
    private[this] var upstreamIsDone = false

    /** Cancellable reference. */
    private val stop: F[Unit] = F.delay(subscription.cancel())

    def consumerLoop(): F[Iterant[F, A]] = {
      def build(array: ArrayBuffer[A], rest: F[Iterant[F, A]]): Iterant[F, A] = {
        val length = if (array ne null) array.length else 0
        length match {
          case 0 => Iterant.suspendS[F, A](rest, stop)
          case 1 => Iterant.nextS[F, A](array.head, rest, stop)
          case _ => Iterant.nextBatchS(Batch.fromAnyArray(array.toArray), rest, stop)
        }
      }

      def awaitOnPromise(): Future[Unit] =
        itemsPromise.get match {
          case null =>
            val p = Promise[Unit]()
            if (itemsPromise.compareAndSet(null, p))
              p.future
            else
              awaitOnPromise()
          case ref =>
            ref.future
        }

      def register(cb: Callback[Iterant[F, A]]): Unit = {
        var array: ArrayBuffer[A] = null

        if (requested == 0) {
          requested = batchSize
          subscription.request(batchSize)
        }

        while (true) {
          queue.relaxedPoll() match {
            case null =>
              if ((array eq null) || array.length == 0) {
                val f = awaitOnPromise()
                // Try to read again
                if (queue.peek() eq null) {
                  f.onComplete(_ => register(cb))
                  return
                }
              } else {
                cb(Right(build(array, consumerLoop())))
                return
              }

            case ref: FinalEvent =>
              ref match {
                case OnComplete =>
                  cb.onSuccess(build(array, F.pure(Iterant.empty[F, A])))
                  return
                case OnError(e) =>
                  cb.onSuccess(build(array, F.pure(Iterant.haltS[F, A](Some(e)))))
                  return
              }

            case elem =>
              if (array eq null) array = ArrayBuffer.empty
              array += elem.asInstanceOf[A]
              requested -= 1
          }
        }
      }

      def start(state: AtomicAny[Promise[Iterant[F, A]]])
        (cb: Either[Throwable, Iterant[F, A]] => Unit): Unit = {

        state.get match {
          case null =>
            val p = Promise[Iterant[F, A]]()
            if (!state.compareAndSet(null, p)) start(state)(cb) else
              register(new Callback[Iterant[F, A]] {
                def onError(ex: Throwable): Unit = {
                  try cb(Left(ex))
                  finally p.failure(ex)
                }

                def onSuccess(v: Iterant[F, A]): Unit = {
                  try cb(Right(v))
                  finally p.success(v)
                }
              })

          case ref: Promise[A] =>
            implicit val ec = immediate
            ref.future.onComplete {
              case Success(a) => cb(Right(a))
              case Failure(e) => cb(Left(e))
            }
        }
      }

      val state = Atomic.withPadding(null : Promise[Iterant[F, A]], LeftRight128)
      F.async(start(state))
    }

    override def onSubscribe(s: Subscription): Unit =
      subscription := s

    override def onNext(t: A): Unit = {
      if (!queue.offer(t.asInstanceOf[AnyRef]))
        throw new IllegalStateException("Buffer overflowed in onNext!")

      val p = itemsPromise.getAndSet(null)
      if (p ne null) p.complete(unit)
    }

    private def signalComplete(e: Throwable): Unit = {
      if (upstreamIsDone)
        throw new IllegalStateException("Protocol violation, multiple onComplete/onError events!")
      upstreamIsDone = true
      if (!queue.offer(if (e eq null) OnComplete else OnError(e)))
        throw new IllegalStateException("Buffer overflowed in onComplete!")
    }

    override def onError(e: Throwable): Unit =
      signalComplete(e)
    override def onComplete(): Unit =
      signalComplete(null)
  }

  /** Reusable Try[Unit] reference. */
  private final val unit: Try[Unit] =
    Applicative[Try].unit

  private sealed abstract class FinalEvent
    extends Serializable
  private case object OnComplete
    extends FinalEvent
  private final case class OnError(e: Throwable)
    extends FinalEvent
}
