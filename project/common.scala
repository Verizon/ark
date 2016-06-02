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
import sbt._, Keys._
import sbtrelease._
import sbtrelease.ReleasePlugin.autoImport._
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities._
import bintray.BintrayKeys._

object common {

  def settings =
    bintraySettings ++
    releaseSettings ++
    publishingSettings ++
    testSettings ++
    customSettings

  val scalaTestVersion    = settingKey[String]("scalatest version")
  val scalaCheckVersion   = settingKey[String]("scalacheck version")
  val scalazStreamVersion = settingKey[String]("scalaz stream version")

  def testSettings = Seq(
    scalaTestVersion     := "2.2.5",
    scalaCheckVersion    := "1.12.3",
    libraryDependencies ++= Seq(
      "org.scalatest"  %% "scalatest"  % scalaTestVersion.value  % "test",
      "org.scalacheck" %% "scalacheck" % scalaCheckVersion.value % "test"
    )
  )

  def ignore = Seq(
    publish := (),
    publishLocal := (),
    publishArtifact in Test := false,
    publishArtifact in Compile := false
  )

  def bintraySettings = Seq(
    bintrayPackageLabels := Seq("mesos", "scheduler", "framework", "functional programming", "scala"),
    bintrayOrganization := Some("oncue"),
    bintrayRepository := "releases",
    bintrayPackage := "mesosscheduler"
  )

  def withoutSnapshot(ver: Version) =
    if(ver.qualifier.exists(_ == "-SNAPSHOT")) ver.withoutQualifier
    else ver.copy(qualifier = ver.qualifier.map(_.replaceAll("-SNAPSHOT", "")))

  def releaseSettings = Seq(
    releaseCrossBuild := false,
    releaseVersion := { ver =>
      sys.env.get("TRAVIS_BUILD_NUMBER").orElse(sys.env.get("BUILD_NUMBER"))
        .map(s => try Option(s.toInt) catch { case _: NumberFormatException => Option.empty[Int] })
        .flatMap(ci => Version(ver).map(v => withoutSnapshot(v).copy(bugfix = ci).string))
        .orElse(Version(ver).map(v => withoutSnapshot(v).string))
        .getOrElse(versionFormatError)
    },
    releaseProcess := Seq(
      Seq[ReleaseStep](
        checkSnapshotDependencies,
        inquireVersions,
        runTest,
        setReleaseVersion,
        commitReleaseVersion,
        tagRelease,
        publishArtifacts,
        setNextVersion,
        commitNextVersion
      ),
      // only job *.1 pushes tags, to avoid each independent job attempting to retag the same release
      Option(System.getenv("TRAVIS_JOB_NUMBER")) filter { _ endsWith ".1" } map { _ =>
        pushChanges.copy(check = identity) } toSeq
    ).flatten
  )

  def publishingSettings = Seq(
    pomExtra := (
      <developers>
        <developer>
          <id>rolandomanrique</id>
          <name>Rolando Manrique</name>
          <url>http://github.com/rolandomanrique</url>
        </developer>
        <developer>
          <id>stew</id>
          <name>Stew O'Connor</name>
          <url>http://github.com/stew</url>
        </developer>
      </developers>),
    publishMavenStyle := true,
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    homepage := Some(url("http://github.com/oncue/mesos-scheduler")),
    scmInfo := Some(ScmInfo(url("https://github.com/oncue/mesos-scheduler"),
                                "git@github.com:oncue/mesos-scheduler.git")),
    pomIncludeRepository := { _ => false },
    publishArtifact in Test := false
  )

  def customSettings = Seq(
    // "0.8.1a" "0.7.3a"
    scalazStreamVersion := {
      sys.env.get("SCALAZ_STREAM_VERSION").getOrElse("0.7.3a")
    },
    unmanagedSourceDirectories in Compile += (sourceDirectory in Compile).value / s"scalaz-stream-${scalazStreamVersion.value.take(3)}",
    version := {
      val suffix = if(scalazStreamVersion.value.startsWith("0.7")) "" else "a"
      val versionValue = version.value
      if(versionValue.endsWith("-SNAPSHOT"))
        versionValue.replaceAll("-SNAPSHOT", s"$suffix-SNAPSHOT")
      else s"$versionValue$suffix"
    }
  )
}
