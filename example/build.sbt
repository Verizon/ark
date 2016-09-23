import AssemblyKeys._

assemblySettings

artifact in (Compile, assembly) ~= { art =>
  art.copy(`classifier` = Some("assembly"))
}

addArtifact(artifact in (Compile, assembly), assembly)

Keys.test in assembly := {}

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % "0.9.3",
  "org.http4s" %% "http4s-blaze-server" % "0.9.3",
  "org.http4s" %% "http4s-argonaut" % "0.9.3"
)

scalacOptions in Test ~= (_.filterNot(Set("-Ywarn-value-discard")))

mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) => {
  case x if x.contains("journal") => MergeStrategy.first
  case x if x.contains("log4j") => MergeStrategy.discard
  case x if x.contains("logback.xml") => MergeStrategy.first
  case x if x.contains("BuildInfo") => MergeStrategy.first
  case x if x.contains("Pimped") => MergeStrategy.first
  case x if x.contains("package") => MergeStrategy.first
  case x if x.contains("ServiceConfig") => MergeStrategy.first
  case x if x.contains("JsonUtil") => MergeStrategy.first
  case x if x.contains("io.netty") => MergeStrategy.first
  case x if x.contains("ConfigLoader") => MergeStrategy.first
  case x => old(x)
}}

mainClass in run := Some("oncue.mesos.example.Main")

mainClass in assembly := Some("oncue.mesos.example.Main")
