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

import journal.Logger
import org.apache.mesos.Protos

object OneTaskPerSlaveState {
  def apply(frameworkName: String): OneTaskPerSlaveState =
    OneTaskPerSlaveState(Set.empty, Set.empty, frameworkName, "not-registered-yet", 0)
}

case class OneTaskPerSlaveState(override val reconcileTasks: Set[ReconcileTaskStatus], blacklist: Set[String],
  frameworkName: String, frameworkId: String, nextId: Int) extends SchedulerState[OneTaskPerSlaveState] {
  val taskId = s"$frameworkName-$nextId-$frameworkId"
}

/**
  * Sample mesos scheduler state manager implementation that runs one task per slave
  */
class OneTaskPerSlaveStateManager(val reqcpu: Double, val reqmem: Double, val cmd: Protos.CommandInfo.Builder)
  extends SimpleSchedulerStateManager[OneTaskPerSlaveState] {

  private val log = Logger[this.type]

  override def processOffer(cpus: Double, mem: Double, slaveId: String)(state: OneTaskPerSlaveState)
  : (OneTaskPerSlaveState, Seq[Protos.TaskInfo.Builder]) = {
    if (reqcpu <= cpus && reqmem <= mem && !state.blacklist.contains(slaveId) &&
      !state.reconcileTasks.exists(_.slaveId == slaveId)) {
      log.debug(s"accepting offer on $slaveId")
      val newTasks = state.reconcileTasks + ReconcileTaskStatus(state.taskId, slaveId)
      val newState = state.copy(nextId = state.nextId+1, reconcileTasks = newTasks)
      (newState, Seq(makeTask(state.taskId, reqcpu, reqmem, cmd)))
    } else {
      (state, Seq.empty)
    }
  }

  override def registered(frameworkId: String)(state: OneTaskPerSlaveState): OneTaskPerSlaveState = {
    state.copy(frameworkId = frameworkId)
  }

  // return false if task should be killed, called when TASK_RUNNING | TASK_STAGING
  override def taskRunning(taskId: String, executorId: String, slaveId: String)(state: OneTaskPerSlaveState)
  : (OneTaskPerSlaveState, Boolean) = {
    if (!state.reconcileTasks.exists(_.slaveId == slaveId)) {
      val newTasks = state.reconcileTasks + ReconcileTaskStatus(taskId, slaveId)
      val newState = state.copy(nextId = state.nextId+1, reconcileTasks = newTasks)
      (newState, true)
    } else {
      (state, true)
    }
  }

  // called when TASK_FINISHED
  override def taskFinished(taskId: String, executorId: String, slaveId: String)(state: OneTaskPerSlaveState)
  : OneTaskPerSlaveState = {
    val newTasks = state.reconcileTasks.filterNot(_.slaveId == slaveId)
    state.copy(reconcileTasks = newTasks)
  }

  // called when TASK_FAILED | TASK_LOST | TASK_ERROR | TASK_KILLED
  override def taskFailed(taskId: String, executorId: String, slaveId: String)(state: OneTaskPerSlaveState)
  : OneTaskPerSlaveState = {
    val newTasks = state.reconcileTasks.filterNot(_.slaveId == slaveId)
    state.copy(reconcileTasks = newTasks)
  }

  // Return Seq[String] with task ids running in the executor
  override def executorLost(executorId: String, slaveId: String, status: Int)(state: OneTaskPerSlaveState)
  : (OneTaskPerSlaveState, Seq[String]) = {
    val newTasks = state.reconcileTasks.filterNot(_.slaveId == slaveId)
    (state.copy(reconcileTasks = newTasks), Seq.empty)
  }

  // Return Seq[String] with task ids running in the slave
  override def slaveLost(slaveId: String)(state: OneTaskPerSlaveState)
  : (OneTaskPerSlaveState, Seq[String]) = {
    val newTasks = state.reconcileTasks.filterNot(_.slaveId == slaveId)
    (state.copy(reconcileTasks = newTasks), Seq.empty)
  }


  def makeTask(id: String, cpus: Double, mem: Double, cmd: Protos.CommandInfo.Builder): Protos.TaskInfo.Builder = {
    Protos.TaskInfo.newBuilder
      .setTaskId(Protos.TaskID.newBuilder.setValue(id))
      .setName(id)
      .addResources(scalarResource("cpus", cpus))
      .addResources(scalarResource("mem", mem))
      .setCommand(cmd)
  }

  protected def scalarResource(name: String, value: Double): Protos.Resource.Builder =
    Protos.Resource.newBuilder
      .setType(Protos.Value.Type.SCALAR)
      .setName(name)
      .setScalar(Protos.Value.Scalar.newBuilder.setValue(value))

}