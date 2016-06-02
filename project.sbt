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
organization in Global := "oncue.mesosscheduler"

scalaVersion in Global := "2.10.6"

scalacOptions in Global := Seq(
  "-deprecation",
  "-feature",
  "-encoding", "utf8",
  "-language:postfixOps",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xcheckinit",
  "-Xfuture",
  "-Xlint",
  "-Xfatal-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-value-discard")

licenses in Global += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))

lazy val mesosscheduler = project.in(file(".")).aggregate(core, example)

lazy val core = project

lazy val example = project.dependsOn(core % "test->test;compile->compile")

releaseCrossBuild := true

publishArtifact in (Compile, packageBin) := false

publish := ()

publishLocal := ()
