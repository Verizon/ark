package verizon.build

import sbt._, Keys._
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

object CentralRequirementsPlugin extends AutoPlugin {

  override def trigger = allRequirements

  override def requires = RigPlugin

  override lazy val projectSettings = Seq(
    sonatypeProfileName := "io.verizon",
    pomExtra in Global := {
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
      </developers>
    },
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    homepage := Some(url("http://verizon.github.io/ark/")),
    scmInfo := Some(ScmInfo(url("https://github.com/verizon/ark"),
                                "git@github.com:verizon/ark.git"))
  )
}
