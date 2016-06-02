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

import java.util
import org.apache.mesos.Protos
import scala.collection.JavaConverters._

// State for reconciliation algorithm (see http://mesos.apache.org/documentation/latest/reconciliation/)
// tasks pending for reconciliation, no offers will be accepted until list is empty
case class ReconcileState(reconciledAt: Long, reconcilingTasks: Set[ReconcileTaskStatus],
  minTaskReconciliationWait: Long = 5000, maxTaskReconciliationWait: Long = 30000) {

  val size = reconcilingTasks.size
  def minTimeElapsed: Boolean = System.currentTimeMillis() - reconciledAt > minTaskReconciliationWait
  def maxTimeElapsed: Boolean = System.currentTimeMillis() - reconciledAt > maxTaskReconciliationWait
  def reconciling: Boolean = reconcilingTasks.nonEmpty || !minTimeElapsed
  def expired: Boolean = reconcilingTasks.nonEmpty && maxTimeElapsed
  def getJavaCollection: util.Collection[Protos.TaskStatus] = reconcilingTasks.map(_.toTaskStatus).asJavaCollection

}

object ReconcileState {
  val empty = ReconcileState(0L, Set.empty)
  def apply(state: SchedulerState[_]): ReconcileState = ReconcileState(System.currentTimeMillis, state.reconcileTasks)
}

// Min info required to create TaskStatus for reconciliation
case class ReconcileTaskStatus(taskId: String, slaveId: String) {
  def toTaskStatus: Protos.TaskStatus = Protos.TaskStatus.newBuilder()
    .setState(Protos.TaskState.TASK_RUNNING)
    .setTaskId(Protos.TaskID.newBuilder.setValue(taskId).build())
    .setSlaveId(Protos.SlaveID.newBuilder.setValue(slaveId).build())
    .build()
}
