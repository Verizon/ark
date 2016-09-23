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
package example

import org.apache.mesos.{MesosSchedulerDriver, Protos}
import org.http4s.server.blaze.BlazeBuilder
import scala.concurrent.duration._
import scala.language.postfixOps

object Main extends scala.App {
  val mesosMaster = "zk://127.0.0.1:2181/mesos"
  val frameworkName = "sample-scheduler"
  val reqcpu = 0.1
  val reqmem = 64.0
  val cmd = Protos.CommandInfo.newBuilder.setShell(true)
    .setValue("""echo "SAMPLE SCHEDULER! sleeping for 120 secs" && sleep 120""")

  val reconciliationInterval = 1 minute
  val frameworkInfo = Protos.FrameworkInfo.newBuilder
    .setName(frameworkName)
    .setUser("")
    .build

  val initialState = OneTaskPerSlaveState(frameworkName)
  val manager = new OneTaskPerSlaveStateManager(reqcpu, reqmem, cmd) with CustomMessageHandler {
    override val master = mesosMaster
  }
  val scheduler = new Scheduler(manager)
  val driver = new MesosSchedulerDriver(scheduler, frameworkInfo, mesosMaster)

  // Set up http service
  val (service, httpStream) = Service.setup(driver)
  val server = BlazeBuilder.bindHttp(9000, System.getenv("LIBPROCESS_IP")).mountService(service, "/").run

  sys addShutdownHook {
    server.shutdownNow()
    scheduler.shutdown(driver)
  }

  // Scheduler companion object provides a process
  // that triggers a reconcile message on a given interval
  val reconcileProcess = Scheduler.reconcileProcess(driver, reconciliationInterval)

  // When running the scheduler we can pass a list of scalaz stream
  // processes to send messages to the state manager, in this case
  // we only provide reconcile process
  scheduler.init(initialState, driver, Seq(reconcileProcess, httpStream)).run

}
