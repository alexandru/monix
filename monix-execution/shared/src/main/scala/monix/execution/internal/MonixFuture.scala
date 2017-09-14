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

package monix.execution.internal

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** In needing to keep compatibility with Scala 2.11, this is a marker
  * trait for `Future` implementations in Monix that implement
  * `transform` and `transformWith` in all supported Scala versions.
  *
  * Internal to Monix only.
  */
private[execution]
trait MonixFuture[+A] { self: Future[A] =>
  def transform[S](f: Try[A] => Try[S])(implicit ec: ExecutionContext): Future[S]
  def transformWith[S](f: Try[A] => Future[S])(implicit ec: ExecutionContext): Future[S]
}
