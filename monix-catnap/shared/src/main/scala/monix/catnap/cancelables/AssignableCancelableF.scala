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

package monix.catnap.cancelables

import monix.catnap.CancelableF

/** Represents a class of cancelable references that can hold
  * an internal reference to another cancelable (and thus has to
  * support the assignment operator).
  *
  * On assignment, if this cancelable is already
  * canceled, then no assignment should happen and the update
  * reference should be canceled as well.
  */
trait AssignableCancelableF[F[_]] extends CancelableF[F] {
  def set(ref: CancelableF[F]): F[Unit]
}

object AssignableCancelableF {
  /**
    * Represents [[AssignableCancelableF]] instances that are also
    * [[BooleanCancelableF]].
    */
  trait Bool[F[_]] extends AssignableCancelableF[F] with BooleanCancelableF[F]

  /**
    * Interface for [[AssignableCancelableF]] types that can be
    * assigned multiple times.
    */
  trait Multi[F[_]] extends Bool[F]
}
