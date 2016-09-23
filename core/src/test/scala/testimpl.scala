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
package ark

import java.util.concurrent.atomic.AtomicInteger

import org.apache.mesos.Protos._
import org.apache.mesos.SchedulerDriver
import scala.collection.JavaConversions._
import java.util

import scala.collection.mutable.ListBuffer

class DriverImpl extends SchedulerDriver {

  val declinedOffers = new ListBuffer[OfferID]()
  val acceptedOffers = new ListBuffer[OfferID]()
  val launchedTasks = new ListBuffer[TaskInfo]()
  val reconciledCount = new AtomicInteger(0)
  def reconciled = reconciledCount.intValue > 0

  override def declineOffer(offerId: OfferID): Status = {
    declinedOffers += offerId
    Status.DRIVER_RUNNING
  }

  override def launchTasks(offerIds: util.Collection[OfferID], tasks: util.Collection[TaskInfo]): Status = {
    acceptedOffers ++= offerIds
    launchedTasks ++= tasks
    Status.DRIVER_RUNNING
  }

  // Mesos 0.23.x
  override def acceptOffers(offerIds: util.Collection[OfferID], ops: util.Collection[Offer.Operation], 
  	filters: Filters): Status = Status.DRIVER_RUNNING

  override def killTask(taskId: TaskID): Status = Status.DRIVER_RUNNING

  override def reconcileTasks(statuses: util.Collection[TaskStatus]): Status = {
    reconciledCount.getAndIncrement()
    Status.DRIVER_RUNNING
  }

  override def suppressOffers(): Status = Status.DRIVER_RUNNING

  override def reviveOffers(): Status = Status.DRIVER_RUNNING

  override def declineOffer(offerId: OfferID, filters: Filters): Status = Status.DRIVER_RUNNING

  override def launchTasks(offerIds: util.Collection[OfferID], tasks: util.Collection[TaskInfo],
                           filters: Filters): Status = Status.DRIVER_RUNNING

  override def launchTasks(offerId: OfferID, tasks: util.Collection[TaskInfo], filters: Filters)
  : Status = Status.DRIVER_RUNNING

  override def launchTasks(offerId: OfferID, tasks: util.Collection[TaskInfo])
  : Status = Status.DRIVER_RUNNING

  override def requestResources(requests: util.Collection[Request])
  : Status = Status.DRIVER_RUNNING

  override def sendFrameworkMessage(executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte])
  : Status = Status.DRIVER_RUNNING

  override def acknowledgeStatusUpdate(ackStatus: TaskStatus)
  : Status = Status.DRIVER_RUNNING

  override def abort(): Status = Status.DRIVER_STOPPED

  override def join(): Status = Status.DRIVER_RUNNING

  override def run(): Status = Status.DRIVER_RUNNING

  override def start(): Status = Status.DRIVER_RUNNING

  override def stop(): Status = Status.DRIVER_STOPPED

  override def stop(failover: Boolean): Status = Status.DRIVER_STOPPED

}

case class StateImpl(override val reconcileTasks: Set[ReconcileTaskStatus]) extends SchedulerState[StateImpl]

class StateManagerImpl(driver: SchedulerDriver, frameworkID: FrameworkID, masterInfo: MasterInfo)
  extends SchedulerStateManager[StateImpl] {
  val received = new ListBuffer[MesosMessage]

  override def processOffer(offer: Offer)(state: StateImpl): (StateImpl, Seq[TaskInfo.Builder]) = {
    received += ResourceOffersMessage(driver,offer)
    (state, Seq.empty)
  }

  override def statusUpdate(status: TaskStatus)(state: StateImpl): (StateImpl, Option[TaskID]) = {
    received += StatusUpdateMessage(driver, status)
    (state, None)
  }

  override def registered(id: String)(state: StateImpl): StateImpl = {
    received += RegisteredMessage(driver, frameworkID, masterInfo)
    state
  }

  override def processCustomMessage(msg: CustomMessage)(state: StateImpl): StateImpl = {
    received += msg
    state
  }

}