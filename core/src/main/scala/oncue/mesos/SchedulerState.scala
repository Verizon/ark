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

import org.apache.mesos.Protos.TaskState._
import org.apache.mesos.Protos._
import org.apache.mesos.Protos

import scala.collection.JavaConverters._


trait SchedulerState[T] { self: T =>
  // Return list of tasks that the framework thinks is running
  def reconcileTasks: Set[ReconcileTaskStatus]
}

case class SimpleSchedulerState(override val reconcileTasks: Set[ReconcileTaskStatus]) extends SchedulerState[SimpleSchedulerState]

/**
  * Very similar interface to org.apache.mesos.Scheduler but completely thread safe as each call is queued as a message
  * and processed one at a time to call the corresponding function in this interface. Each function receives the
  * current scheduler state and it is expected to return the new state after processing each message.
  */
trait SchedulerStateManager[T <: SchedulerState[T]] {

  // Return tasks to run
  def processOffer(offer: Offer)(state: T): (T, Seq[TaskInfo.Builder])

  // Return Some(TaskID) if we need to kill this task
  def statusUpdate(status: TaskStatus)(state: T): (T, Option[TaskID]) = (state, None)

  // Return task ids running on the lost slave
  def slaveLost(slaveId: SlaveID)(state: T): (T, Seq[TaskID]) = (state, Seq.empty)

  // Return task ids running on the lost executor
  def executorLost(executorId: ExecutorID, slaveId: SlaveID, status: Int)(state: T): (T, Seq[TaskID]) = (state, Seq.empty)

  // Registered also has MasterInfo but its never used so ignoring in this case
  def registered(frameworkId: String)(state: T): T = state
  def reregistered(state: T): T = state

  // Handle custom messages
  def processCustomMessage(msg: CustomMessage)(state: T): T = state

  /*** Non-required methods, less common used ***/
  def rescindOffer(offerId: OfferID)(state: T): T = state
  def frameworkMessage(executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte])(state: T): T = state
  def error(message: String)(state: T): T = state

}

/**
  * SimpleSchedulerState is a very simple implementation of SchedulerState that hides
  * org.apache.mesos.Protos as much as possible in favor of native scala types.
  */
trait SimpleSchedulerStateManager[T <: SchedulerState[T]] extends SchedulerStateManager[T] {

  // called when new offers come in
  def processOffer(cpus: Double, mem: Double, slaveId: String)(state: T): (T, Seq[TaskInfo.Builder])

  // simple impl of process offer that only extracts cpus/mem resources and slave ID
  override def processOffer(offer: Offer)(state: T): (T, Seq[TaskInfo.Builder]) = {
    val res = scalarResources(offer)
    processOffer(res.getOrElse("cpus", 0.0), res.getOrElse("mem", 0.0), offer.getSlaveId.getValue)(state: T)
  }

  // return false if task should be killed, called when TASK_RUNNING | TASK_STAGING
  def taskRunning(taskId: String, executorId: String, slaveId: String)(state: T): (T, Boolean)

  // called when TASK_FINISHED
  def taskFinished(taskId: String, executorId: String, slaveId: String)(state: T): T

  // called when TASK_FAILED | TASK_LOST | TASK_ERROR | TASK_KILLED
  def taskFailed(taskId: String, executorId: String, slaveId: String)(state: T): T

  // Simpler implementation of statusUpdate that opaques Protos.TaskState from user implementation
  override def statusUpdate(status: TaskStatus)(state: T): (T, Option[TaskID]) = {
    val tid = status.getTaskId
    val eid = status.getExecutorId
    val sid = status.getSlaveId
    status.getState match {
      case TASK_RUNNING | TASK_STAGING | TASK_STARTING =>
        val res = taskRunning(tid.getValue, eid.getValue, sid.getValue)(state)
        if (!res._2)
          (res._1, Option(tid))
        else
          (res._1, None)

      case TASK_FINISHED =>
        val res = taskFinished(tid.getValue, eid.getValue, sid.getValue)(state)
        (res, None)

      case TASK_FAILED | TASK_LOST | TASK_ERROR | TASK_KILLED =>
        val res = taskFailed(tid.getValue, eid.getValue, sid.getValue)(state)
        (res, None)
    }
  }

  // Simpler implementation of executorLost that opaques Protos._ from user implementation
  override def executorLost(executorId: ExecutorID, slaveId: SlaveID, status: Int)(state: T): (T, Seq[TaskID]) = {
    val res = executorLost(executorId.getValue, slaveId.getValue, status)(state: T)
    (res._1, res._2.map(TaskID.newBuilder.setValue(_).build))
  }

  // Return Seq[String] with task ids running in the executor
  def executorLost(executorId: String, slaveId: String, status: Int)(state: T): (T, Seq[String])

  // Simpler implementation of slaveLost that opaques Protos._ from user implementation
  override def slaveLost(slaveId: SlaveID)(state: T): (T, Seq[TaskID]) = {
    val res = slaveLost(slaveId.getValue)(state)
    (res._1, res._2.map(TaskID.newBuilder.setValue(_).build))
  }

  // Return Seq[String] with task ids running in the slave
  def slaveLost(slaveId: String)(state: T): (T, Seq[String])

  def scalarResources(offer: Offer): Map[String,Double] = {
    offer.getResourcesList.asScala.toSet
      // Filter scalar resources
      .filter(x => x.getType == Protos.Value.Type.SCALAR)
      // Extract resource name and scalar value
      .map(x=>(x.getName, x.getScalar.getValue))
      // Group by name
      .groupBy(_._1)
      // Add up values
      .mapValues(x => x.map(_._2).sum)
  }

}

