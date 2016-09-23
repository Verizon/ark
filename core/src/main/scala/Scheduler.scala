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

import java.util.Collections

import journal.Logger
import org.apache.mesos
import org.apache.mesos.Protos._
import org.apache.mesos.{Protos, SchedulerDriver}

import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.concurrent.duration._
import scalaz.Nondeterminism
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.{time, Process, Process1, async}
import java.util.concurrent.{Executors, ExecutorService, ThreadFactory}

/**
  * Thread safe implementation of org.apache.mesos.Scheduler based on scalaz streams. Every call to the Scheduler
  * interface is enqueued and process one at a time.
  *
  * User is required to implement and provide oncue.mesos.SchedulerStateManager. Users are expected to implement
  * CustomMessage's to trigger state mutation events in their state managers. CustomMessage's can be passed into
  * processMessage by providing scalaz.stream.Processes in the `customEvents` param passed to `init` function.
  *
  * This class also implements reconciliation algorithm as an additional message in the queue. User can pass
  * Scheduler.reconcileProcess to `init` function to trigger reconciliation in periodic intervals or define custom
  * reconciliation triggers. User must also provide list of tasks that it expects to be running, all offers will
  * be declined until state for all tasks has been received.
  */
class Scheduler[T <: SchedulerState[T]](stateManager: SchedulerStateManager[T]) extends mesos.Scheduler {

  private val log = Logger[this.type]
  private val inbound = async.boundedQueue[MesosMessage](100)(Scheduler.defaultExecutor)

  /**
   * @param state initial state
   * @param driver SchedulerDriver
   * @param customEvents Seq[ Process[ Task, CustomMessage ] ] Processes that generate custom messages to trigger
   *                     state mutation events in the manager. These need to managed my SchedulerStateManager
   *                     implementation in the `processCustomMessage(msg: CustomMessage)` function.
   */
  def init(state: T, driver: SchedulerDriver, customEvents: Seq[Process[Task,CustomMessage]]): Task[Unit] = {
    // merge all inbound processes and define pipe into Process1
    val inboundProcess: Process[Task, MesosMessage] =
      customEvents.foldLeft(inbound.dequeue)((a,b) => a.merge(b)(Scheduler.defaultExecutor))
    val sunkProcess: Process[Task,Unit] = inboundProcess pipe processMessage(state, ReconcileState.empty)

    // prepare to run mesos driver
    val driverTask = Task.fork(Task.delay{
      driver.run()
      ()
    })(Scheduler.defaultPool)

    // prepare to run process
    val streamTask = Task.fork(sunkProcess.run)(Scheduler.defaultPool)

    // return composed Task to be run by caller
    Nondeterminism[Task].gatherUnordered(Seq(driverTask, streamTask)).map(_ => ())
  }

  /**
    * graceful shutdown
    * @param driver SchedulerDriver
    * @param failover see SchedulerDriver.stop docs
    */
  def shutdown(driver: SchedulerDriver, failover: Boolean = false): Unit = {
    log.info(s"stopping driver...")
    driver.stop(failover)
    log.debug(s"stopping stream process...")
    inbound.kill.run
  }

  def processMessage(initialState: T, initialReconcileState: ReconcileState): Process1[MesosMessage,Unit] = {

    def receive(state: T, reconcileState: ReconcileState, msg: MesosMessage): (T, ReconcileState) = msg match {
      case ResourceOffersMessage(driver, offer) =>
        if (!reconcileState.reconciling) {
          stateManager.processOffer(offer)(state) match {
            case (newState, s) if s.nonEmpty =>
              val x: Seq[TaskInfo] = s.map(_.setSlaveId(offer.getSlaveId).build())
              log.info(s"accepting offer ${offer.getId.getValue}@${offer.getSlaveId.getValue} " +
                s"launching tasks: ${x.map(_.getTaskId.getValue)}")
              driver.launchTasks(Collections.singleton(offer.getId), x.asJava)
              (newState, reconcileState)
            case (newState, _) =>
              log.debug(s"declining offer ${offer.getId.getValue}@${offer.getSlaveId.getValue}")
              driver.declineOffer(offer.getId)
              (newState, reconcileState)
          }
        } else {
          log.info(s"declining all offers while reconciling task status, ${reconcileState.size} remaining")
          driver.declineOffer(offer.getId)
          val newReconcileState = checkReconciliation(reconcileState, driver)
          (state, newReconcileState)
        }

      case OfferRescindedMessage(driver, offerId) =>
        (stateManager.rescindOffer(offerId)(state), reconcileState)

      case RegisteredMessage(driver, frameworkId, masterInfo) =>
        (stateManager.registered(frameworkId.getValue)(state), reconcileState)

      case ReregisteredMessage(driver, masterInfo) =>
        (stateManager.reregistered(state), reconcileState)

      case FrameworkMessageMessage(driver, executorId, slaveId, data) =>
        (stateManager.frameworkMessage(executorId, slaveId, data)(state), reconcileState)

      case StatusUpdateMessage(driver, status) =>
        // if reconciling, remove task from remaining tasks
        val newReconcileState = if (reconcileState.reconciling) {
          val filteredState = reconcileState.copy(
            reconcilingTasks = reconcileState.reconcilingTasks.filterNot(_.taskId == status.getTaskId.getValue))
          checkReconciliation(filteredState, driver)
        } else {
          reconcileState
        }

        val (newState, killTasks) = stateManager.statusUpdate(status)(state)
        killTasks.map(driver.killTask)
        (newState, newReconcileState)

      case SlaveLostMessage(driver, slaveId) =>
        // TODO: Do we need to kill this task in case slave comes back up??? We would want to kill it in that case
        val (newState, killTasks) = stateManager.slaveLost(slaveId)(state)
        killTasks.map(driver.killTask)
        (newState, reconcileState)

      case ExecutorLostMessage(driver, executorId, slaveId, status) =>
        // TODO: Do we need to kill this task in case executor comes back up???  We would want to kill it in that case
        val (newState, killTasks) = stateManager.executorLost(executorId, slaveId, status)(state)
        killTasks.map(driver.killTask)
        (newState, reconcileState)

      case ErrorMessage(driver, message) =>
        log.error(s"error message from mesos master: $message")
        (stateManager.error(message)(state), reconcileState)

      case ReconcileMessage(driver) =>
        (state, startReconciliation(state, driver))

      case x: CustomMessage =>
        (stateManager.processCustomMessage(x)(state), reconcileState)
    }

    def go(state: T, reconcileState: ReconcileState): Process1[MesosMessage,Unit] = Process.receive1 { msg =>
      log.debug(s"message received ${msg.getClass}")
      val (next, nextReconcile) = receive(state, reconcileState, msg)

      // after processing each message we need to compare tasks in new state against tasks in previous state
      // to determine what tasks need to be killed
      state.reconcileTasks.diff(next.reconcileTasks).foreach(t =>
        msg.driver.killTask(Protos.TaskID.newBuilder().setValue(t.taskId).build))

      Process.emit(()) ++ go(next, nextReconcile)
    }

    go(initialState, initialReconcileState)
  }

  // Update mutable reconciliation state and request all tasks to be reconciled
  // NOT THREAD SAFE!!! it should only be called within `processMessage` function
  private def startReconciliation(state: T, driver: SchedulerDriver): ReconcileState = {
    log.info(s"starting task reconciliation for all tasks")
    val reconcileState = ReconcileState(state)
    driver.reconcileTasks(Seq.empty[Protos.TaskStatus].asJavaCollection)
    reconcileState
  }

  // Check in max reconciliation wait time has elapsed, resend reconciliation request for remaining tasks
  // NOT THREAD SAFE!!! it should only be called within `processMessage` function
  private def checkReconciliation(reconcileState: ReconcileState, driver: SchedulerDriver): ReconcileState = {
    if (reconcileState.expired) {
      log.info(s"starting task reconciliation for remaining ${reconcileState.size} tasks")
      val newReconcileState = reconcileState.copy(reconciledAt = System.currentTimeMillis)
      driver.reconcileTasks(newReconcileState.getJavaCollection)
      newReconcileState
    } else {
      reconcileState
    }
  }

  override def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]): Unit = {
    // log.debug(s"received ${offers.size} offers")
    // TODO: Group offers by slave ID to be able to combine one big task in the resources of multiple offers
    inbound.enqueueAll(offers.asScala.map(o => ResourceOffersMessage(driver, o))).run
  }


  override def offerRescinded(driver: SchedulerDriver, offerId: OfferID): Unit = {
    log.info(s"offer [${offerId.getValue}] has been rescinded")
    inbound.enqueueOne(OfferRescindedMessage(driver, offerId)).run
  }

  override def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte])
  : Unit = {
    log.info(s"frameworkMessage slave=${slaveId.getValue} executor=${executorId.getValue} data size=${data.length}")
    inbound.enqueueOne(FrameworkMessageMessage(driver, executorId, slaveId, data)).run
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
    log.info(s"statusUpdate ${status.getState} ${status.getTaskId.getValue}: ${status.getMessage}")
    inbound.enqueueOne(StatusUpdateMessage(driver, status)).run
  }

  override def slaveLost(driver: SchedulerDriver, slaveId: SlaveID): Unit = {
    log.info(s"slaveLost ${slaveId.getValue}")
    inbound.enqueueOne(SlaveLostMessage(driver, slaveId)).run
  }

  override def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int): Unit = {
    log.info(s"executorLost slave=${slaveId.getValue} executor=${executorId.getValue} status=$status")
    inbound.enqueueOne(ExecutorLostMessage(driver, executorId, slaveId, status)).run
  }

  override def error(driver: SchedulerDriver, message: String): Unit = {
    log.error(s"Scheduler error: $message")
    inbound.enqueueOne(ErrorMessage(driver, message)).run
  }

  // When framework registers it is recommended to trigger reconciliation, sending a RegisteredMessage first
  // to allow state manager to initialize state before starting reconciliation.
  override def registered(driver: SchedulerDriver, frameworkId: FrameworkID, masterInfo: MasterInfo): Unit = {
    val host = masterInfo.getHostname
    val port = masterInfo.getPort
    val id = frameworkId.getValue
    log.info(s"Registered with Mesos master [$host:$port] frameworkID=$id")
    inbound.enqueueAll(Seq(RegisteredMessage(driver, frameworkId, masterInfo), ReconcileMessage(driver))).run
  }

  // When framework reregisters it is recommended to trigger reconciliation, sending a ReregisteredMessage first
  // to allow state manager to initialize state before starting reconciliation.
  override def reregistered(driver: SchedulerDriver, masterInfo: MasterInfo): Unit = {
    log.info(s"Reregistered with Mesos master ${masterInfo.getHostname}:${masterInfo.getPort}")
    inbound.enqueueAll(Seq(ReregisteredMessage(driver, masterInfo), ReconcileMessage(driver))).run
  }

  override def disconnected(driver: SchedulerDriver): Unit = {
    log.error(s"Disconnected from Mesos master...")
  }

}

object Scheduler {
  // this process is used to trigger reconciliation every few mins and it can be passed in to Scheduler
  def reconcileProcess(driver: SchedulerDriver, reconcileInterval: FiniteDuration): Process[Task, ReconcileMessage] = {
    time.awakeEvery(reconcileInterval)(defaultExecutor, timeOutScheduler)
      .map(_ => ReconcileMessage(driver))
  }

  private def daemonThreads(name: String) = new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  }

  val defaultPool: ExecutorService = Executors.newFixedThreadPool(10, daemonThreads("scheduler"))
  val defaultExecutor: Strategy = Strategy.Executor(defaultPool)
  val timeOutScheduler = Executors.newScheduledThreadPool(10, daemonThreads("scheduler-sleep"))

}

object `package` {
  implicit val dontUseTheDefaultStrategy: scalaz.concurrent.Strategy = null
  implicit val theDefaultStrategyCausesProblems: scalaz.concurrent.Strategy = null
}
