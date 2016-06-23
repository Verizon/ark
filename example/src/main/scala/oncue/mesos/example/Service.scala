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
import org.http4s.EntityEncoder
import org.http4s.server.{HttpService, Router}
import org.http4s.argonaut._
import org.http4s.dsl._
import argonaut._
import Argonaut._
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scalaz.concurrent.Task
import scalaz.stream.async
import scalaz.stream.async.mutable.Queue

object Service {

  implicit val infoEncoder: EntityEncoder[SchedulerInfo] = jsonEncoderOf[SchedulerInfo]
  implicit def infoJson: CodecJson[SchedulerInfo] = casecodec5(SchedulerInfo.apply, SchedulerInfo.unapply)(
    "mesosMaster", "frameworkId", "frameworkName", "reqcpu", "reqmem")

  def setup(driver: MesosSchedulerDriver) = {
    val inbound = async.boundedQueue[CustomMessage](100)(Scheduler.defaultExecutor)
    val stream = inbound.dequeue
    (service(inbound, driver), stream)
  }

  def service(inbound: Queue[CustomMessage], driver: MesosSchedulerDriver)(
    implicit executionContext: ExecutionContext = ExecutionContext.global): HttpService =
    Router("" -> rootService(inbound, driver))

  def rootService(inbound: Queue[CustomMessage], driver: MesosSchedulerDriver)(
    implicit executionContext: ExecutionContext) = HttpService {

    case _ -> Root => MethodNotAllowed()

    case GET -> Root / "info" => {
      // When request comes we only block until message makes it to the queue
      // After that is just waiting for state manager to call callback function
      val res: Task[SchedulerInfo] = Task.async[SchedulerInfo](cb => inbound.enqueueOne(GetInfo(driver, cb)).run)
      Ok(res)
    }

    case POST -> Root / "blacklist" / slaveId => {
      inbound.enqueueOne(Blacklist(driver, slaveId)).run
      Ok(s"Requested Blacklist $slaveId")
    }

    case DELETE -> Root / "blacklist" / slaveId => {
      inbound.enqueueOne(Unblacklist(driver, slaveId)).run
      Ok(s"Requested Unblacklist $slaveId")
    }
  }

}
