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

import org.scalatest._
import scala.collection.JavaConverters._
import org.apache.mesos.Protos

class OneTaskPerSlaveStateManagerSpec extends FlatSpec with MustMatchers {

  def cmdBuilder(cmd:String) = Protos.CommandInfo.newBuilder.setShell(true).setValue(cmd)

  behavior of "OneTaskPerSlaveStateManager"
  it should "accept any offer with enough resources and run the correct command" in {
    val frameworkName = "some-framework"
    val reqcpu = 1.0
    val reqmem = 1024.0
    val cmd = cmdBuilder("java -jar my-assembly.jar")
    val state = OneTaskPerSlaveState(frameworkName)
    val mgr = new OneTaskPerSlaveStateManager(reqcpu, reqmem, cmd)

    val t = mgr.processOffer(2.0, 2048.0, "someslaveid")(state)._2.head
    t.getCommand.getValue must equal(cmd.getValue)
    t.getResourcesList.asScala.foreach(x => x.getName match {
      case "cpus" => x.getScalar.getValue must equal(reqcpu)
      case "mem" => x.getScalar.getValue must equal(reqmem)
    })
  }

  it should "reject any offer in a slave where a task is already running" in {
    val frameworkName = "some-framework"
    val reqcpu = 1.0
    val reqmem = 1024.0
    val cmd = cmdBuilder("java -jar my-assembly.jar")
    val state1 = OneTaskPerSlaveState(frameworkName)
    val mgr = new OneTaskPerSlaveStateManager(reqcpu, reqmem, cmd)

    val (state2, s1) = mgr.processOffer(2.0, 2048.0, "someslaveid")(state1)
    s1 must not equal Seq.empty
    val (state3, s2) = mgr.processOffer(2.0, 2048.0, "someslaveid")(state2)
    s2 must equal(Seq.empty)
  }

  it should "list reconcile tasks correctly" in {
    val frameworkName = "some-framework"
    val reqcpu = 1.0
    val reqmem = 1024.0
    val cmd = cmdBuilder("java -jar my-assembly.jar")
    val state1 = OneTaskPerSlaveState(frameworkName)
    val mgr = new OneTaskPerSlaveStateManager(reqcpu, reqmem, cmd)
    val (state2, s1) = mgr.processOffer(2.0, 2048.0, "someslaveid1")(state1)
    s1 must not equal Seq.empty
    val (state3, s2) = mgr.processOffer(2.0, 2048.0, "someslaveid2")(state2)
    s2 must not equal Seq.empty
    val (state4, s3) = mgr.processOffer(2.0, 2048.0, "someslaveid3")(state3)
    s3 must not equal Seq.empty

    val exp = ReconcileTaskStatus(state1.taskId, "someslaveid1") ::
      ReconcileTaskStatus(state2.taskId, "someslaveid2") ::
      ReconcileTaskStatus(state3.taskId, "someslaveid3") ::
      Nil
    state4.reconcileTasks.toList.sortBy(_.slaveId) must equal(exp)
  }
}