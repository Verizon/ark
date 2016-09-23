
libraryDependencies ++= Seq(
  "io.verizon.journal" %% "core"          % "2.2.1",
  "org.scalaz.stream"  %% "scalaz-stream" % "0.7.3a",
  "org.apache.mesos"    % "mesos"         % "0.26.0"
)

scalacOptions in Test ~= (_.filterNot(Set("-Ywarn-value-discard")))

ivyScala := ivyScala.value map { _.copy(overrideScalaVersion = true) }
