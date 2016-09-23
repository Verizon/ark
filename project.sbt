
organization in Global := "io.verizon.ark"

scalaVersion in Global := "2.10.6"

lazy val ark = project.in(file(".")).aggregate(core, example)

lazy val core = project

lazy val example = project.dependsOn(core % "test->test;compile->compile")

enablePlugins(DisablePublishingPlugin)
