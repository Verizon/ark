//: ----------------------------------------------------------------------------
//: Copyright (C) 2016 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package oncue.mesos

import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver

// Model every possible message coming from mesos master
sealed trait MesosMessage { def driver: SchedulerDriver }
case class ResourceOffersMessage(override val driver: SchedulerDriver, offer: Offer)
  extends MesosMessage
case class OfferRescindedMessage(override val driver: SchedulerDriver, offerId: OfferID)
  extends MesosMessage
case class RegisteredMessage(override val driver: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo)
  extends MesosMessage
case class ReregisteredMessage(override val driver: SchedulerDriver, masterInfo: MasterInfo)
  extends MesosMessage
case class FrameworkMessageMessage(override val driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID,
  data: Array[Byte]) extends MesosMessage
case class StatusUpdateMessage(override val driver: SchedulerDriver, status: TaskStatus)
  extends MesosMessage
case class SlaveLostMessage(override val driver: SchedulerDriver, slaveId: SlaveID)
  extends MesosMessage
case class ExecutorLostMessage(override val driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID,
  status: Int) extends MesosMessage
case class ErrorMessage(override val driver: SchedulerDriver, message: String)
  extends MesosMessage

// Users can extend CustomMessage to trigger state mutations through Scheduler.customEvents Process
trait CustomMessage extends MesosMessage
case class ReconcileMessage(override val driver: SchedulerDriver)
  extends CustomMessage
