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

import java.util.UUID

import journal.Logger
import org.apache.mesos.Protos.{MasterInfo, FrameworkID, Offer}
import org.apache.mesos.{SchedulerDriver, Protos}
import org.scalatest._
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scalaz.concurrent.Task
import scalaz.stream.async


class SchedulerSpec extends FlatSpec with MustMatchers {

  private val log = Logger[this.type]

  behavior of "Scheduler"

  it should "process messages" in {
    val driver = new DriverImpl
    val expFwId = fwId()
    val expOffer = offer(expFwId)
    val expmi = masterInfo()
    val expStatus = taskStatus()

    // rebuild MesosMessage and add to received list
    val st = StateImpl(Set.empty)
    val mgr = new StateManagerImpl(driver, expFwId, expmi)
    val scheduler = new Scheduler(mgr)
    scheduler.init(st, driver, Seq.empty).runAsync { case _ => /* noop */ }
    Thread.sleep(500)
    scheduler.registered(driver, expFwId, expmi)
    Thread.sleep(6000) // TODO: need to sleep ReconcileState.minTaskReconciliationWait or offers will be declined
    driver.reconciled must equal(true)
    scheduler.resourceOffers(driver, List(expOffer).asJava)
    scheduler.statusUpdate(driver, expStatus)
    Thread.sleep(1000)
    scheduler.shutdown(driver)

    val expReceived = RegisteredMessage(driver, expFwId, expmi) ::
      ResourceOffersMessage(driver, expOffer) ::
      StatusUpdateMessage(driver, expStatus) ::
      Nil
    mgr.received.toList must equal(expReceived)

  }

  it should "process custom messages" in {
    case class MyCustomMessage(driver: SchedulerDriver, id: String) extends CustomMessage
    val driver = new DriverImpl
    val expFwId = fwId()
    val expmi = masterInfo()
    val expCustMsg = MyCustomMessage(driver, genid)

    val customEventsQueue = async.boundedQueue[MyCustomMessage](100)(Scheduler.defaultExecutor)
    val customEvents = customEventsQueue.dequeue

    // rebuild MesosMessage and add to received list
    val st = StateImpl(Set.empty)
    val mgr = new StateManagerImpl(driver, expFwId, expmi)
    val scheduler = new Scheduler(mgr)
    scheduler.init(st, driver, Seq(customEvents)).runAsync { case _ => /* noop */ }
    Thread.sleep(500)
    scheduler.registered(driver, expFwId, expmi)
    Thread.sleep(6000) // TODO: need to sleep ReconcileState.minTaskReconciliationWait or offers will be declined
    driver.reconciled must equal(true)
    customEventsQueue.enqueueOne(expCustMsg).run
    Thread.sleep(1000)
    scheduler.shutdown(driver)

    val expReceived = RegisteredMessage(driver, expFwId, expmi) :: expCustMsg :: Nil
    mgr.received.toList must equal(expReceived)

  }

  it should "decline all offers when reconciling and remove task from pending when status arrives" in {
    val driver = new DriverImpl
    val expFwId = fwId()
    val declinedOffer = offer(expFwId)
    val passedOffer = offer(expFwId)
    val expmi = masterInfo()
    val expTaskId = genid
    val expSlaveId = genid
    val expStatus = taskStatus(taskId = expTaskId, slaveId = expSlaveId)

    // rebuild MesosMessage and add to received list
    val st = StateImpl(Set(ReconcileTaskStatus(expTaskId, expSlaveId)))
    val mgr = new StateManagerImpl(driver, expFwId, expmi)
    val scheduler = new Scheduler(mgr)
    scheduler.init(st, driver, Seq.empty).runAsync { case _ => /* noop */ }
    Thread.sleep(500)
    scheduler.registered(driver, expFwId, expmi)
    Thread.sleep(5000) // TODO: need to sleep ReconcileState.minTaskReconciliationWait or offers will be declined
    driver.reconciled must equal(true)

    // offers should be declined when reconciling and it should not even make it to the state manager
    scheduler.resourceOffers(driver, List(declinedOffer).asJava)
    scheduler.resourceOffers(driver, List(declinedOffer).asJava)
    scheduler.resourceOffers(driver, List(declinedOffer).asJava)
    scheduler.resourceOffers(driver, List(declinedOffer).asJava)
    Thread.sleep(500)

    val declinedOffers = List(declinedOffer.getId, declinedOffer.getId, declinedOffer.getId, declinedOffer.getId)
    driver.declinedOffers.toList must equal(declinedOffers)
    val expReceived = RegisteredMessage(driver, expFwId, expmi) :: Nil
    mgr.received.toList must equal(expReceived)

    // once status arrives for pending tasks, offers should make it to the state manager
    scheduler.statusUpdate(driver, expStatus)

    scheduler.resourceOffers(driver, List(passedOffer).asJava)
    Thread.sleep(1000)
    scheduler.shutdown(driver)

    val expReceived2 = expReceived ++ (
      StatusUpdateMessage(driver, expStatus) ::
      ResourceOffersMessage(driver, passedOffer) ::
      Nil)
    mgr.received.toList must equal(expReceived2)
    driver.declinedOffers.toList must equal(declinedOffers ++ List(passedOffer.getId))
  } 

  it should "start reconciliation every reconcileInterval" in {
    val reconciliationInterval = 1 second
    val driver = new DriverImpl
    val expFwId = fwId()
    val expmi = masterInfo()

    val reconcileEvents = Scheduler.reconcileProcess(driver, reconciliationInterval)

    // rebuild MesosMessage and add to received list
    val st = StateImpl(Set.empty)
    val mgr = new StateManagerImpl(driver, expFwId, expmi)
    val scheduler = new Scheduler(mgr)
    scheduler.init(st, driver, Seq(reconcileEvents)).runAsync { case _ => /* noop */ }
    Thread.sleep(500)
    scheduler.registered(driver, expFwId, expmi)
    Thread.sleep(5000) // TODO: need to sleep ReconcileState.minTaskReconciliationWait or offers will be declined
    driver.reconciled must equal(true)
    Thread.sleep(5000)
    scheduler.shutdown(driver)

    // slept 5 seconds so make sure we got reconcile message at least 4 times
    driver.reconciledCount.intValue must be >= 4
  }

  it should "run one big task by combining smaller offers" in pendingUntilFixed {
    val driver = new DriverImpl
    val expFwId = fwId()
    val slaveId = genid
    val expOffer1 = offer(expFwId, slaveId = slaveId) // 3 cpus 4000 mem
    val expOffer2 = offer(expFwId, slaveId = slaveId) // 3 cpus 4000 mem
    val expmi = masterInfo()
    val expLaunchedTask = Protos.TaskInfo.newBuilder
      .setTaskId(Protos.TaskID.newBuilder.setValue("myTaskId"))
      .setName("myTaskName")
      .addResources(scalarResource("cpus", 5))
      .addResources(scalarResource("mem", 6000))
      .setCommand(Protos.CommandInfo.newBuilder.setShell(true).setValue("some command here"))

    val st = StateImpl(Set.empty)
    val mgr = new StateManagerImpl(driver, expFwId, expmi) {
      override def processOffer(offer: Offer)(state: StateImpl) = {
        if (state.reconcileTasks.isEmpty) {
          val newState = state.copy(reconcileTasks = state.reconcileTasks + ReconcileTaskStatus("myTaskId", ""))
          (newState, Seq(expLaunchedTask))
        } else {
          (state, Seq.empty)
        }
      }
    }

    val scheduler = new Scheduler(mgr)
    scheduler.init(st, driver, Seq.empty).runAsync { case _ => /* noop */ }
    Thread.sleep(500)
    scheduler.registered(driver, expFwId, expmi)
    Thread.sleep(5000) // TODO: need to sleep ReconcileState.minTaskReconciliationWait or offers will be declined
    driver.reconciled must equal(true)
    scheduler.resourceOffers(driver, List(expOffer1, expOffer2).asJava)
    Thread.sleep(1000)
    scheduler.shutdown(driver)

    val expLaunchedTasks = List(expLaunchedTask.setSlaveId(Protos.SlaveID.newBuilder.setValue(slaveId)).build)
    val expAcceptedOffers = List(expOffer1.getId, expOffer2.getId)
    driver.launchedTasks.toList must equal(expLaunchedTasks)

    // TODO: This will fail until we we combine offers
    driver.acceptedOffers.toList must equal(expAcceptedOffers)

  }

  private def genid = UUID.randomUUID().toString

  private def fwId(id: String = genid) = Protos.FrameworkID.newBuilder.setValue(id).build

  private def scalarResource(name: String, value: Double): Protos.Resource = {
    Protos.Resource.newBuilder
      .setName(name)
      .setType(Protos.Value.Type.SCALAR)
      .setScalar(Protos.Value.Scalar.newBuilder().setValue(value))
      .build
  }

  private def offer(fwid: Protos.FrameworkID, id: String = genid, slaveId: String = genid): Protos.Offer = {
    val resources = Seq(
      scalarResource("cpus", 3),
      scalarResource("mem", 4000),
      scalarResource("disk", 1000000)
    ).asJava

    Protos.Offer.newBuilder
      .setId(Protos.OfferID.newBuilder.setValue(id))
      .setFrameworkId(fwid)
      .setSlaveId(Protos.SlaveID.newBuilder.setValue(slaveId))
      .setHostname("hostname")
      .addAllResources(resources)
      .build
  }

  private def masterInfo(id: String = genid): Protos.MasterInfo = {
    Protos.MasterInfo.newBuilder
      .setHostname("localhost")
      .setIp(123)
      .setId(id)
      .setPort(5050)
      .setVersion("0.25.0")
      .build

  }

  private def taskStatus(state: Protos.TaskState = Protos.TaskState.TASK_RUNNING, taskId: String = genid,
    slaveId: String = genid) = {
    Protos.TaskStatus.newBuilder
      .setTaskId(Protos.TaskID.newBuilder.setValue(taskId).build)
      .setSlaveId(Protos.SlaveID.newBuilder.setValue(slaveId))
      .setExecutorId(Protos.ExecutorID.newBuilder.setValue(genid))
      .setState(state)
      .build
  }

}
