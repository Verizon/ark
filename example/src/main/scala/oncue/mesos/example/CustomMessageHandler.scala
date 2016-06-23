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
package oncue.mesos.example

import oncue.mesos._
import org.apache.mesos.MesosSchedulerDriver

import scalaz.\/

case class SchedulerInfo(mesosMaster: String, frameworkId: String, frameworkName: String, reqcpu: Double, reqmem: Double)
case class GetInfo(override val driver: MesosSchedulerDriver, cb: (Throwable \/ SchedulerInfo) => Unit) extends CustomMessage
case class Blacklist(override val driver: MesosSchedulerDriver, slaveId: String) extends CustomMessage
case class Unblacklist(override val driver: MesosSchedulerDriver, slaveId: String) extends CustomMessage

trait CustomMessageHandler { self: OneTaskPerSlaveStateManager =>

  def master: String

  override def processCustomMessage(msg: CustomMessage)(state: OneTaskPerSlaveState): OneTaskPerSlaveState = msg match {
    // GetInfo message comes from HTTP service endpoints and provides a callback function that will be serialized into
    // the http response, we just need to create SchedulerInfo and pass it to the callback function
    case GetInfo(_, cb) =>
      val info: SchedulerInfo = SchedulerInfo(master, state.frameworkId, state.frameworkName, reqcpu, reqmem)
      cb(\/.right(info))
      state

    // Remove any tasks running and add slaveId to blacklist
    case Blacklist(_, slaveId) =>
      state.copy(reconcileTasks = state.reconcileTasks.filterNot(_.slaveId == slaveId), blacklist = state.blacklist + slaveId)

    // Remove slaveId from blacklist
    case Unblacklist(_, slaveId) =>
      state.copy(blacklist = state.blacklist.filterNot(_ == slaveId))

  }

}