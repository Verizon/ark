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
resolvers += Resolver.url(
  "bintray-sbt-plugin-releases",
    url("http://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
        Resolver.ivyStylePatterns)

resolvers += Resolver.url(
  "tpolecat-sbt-plugin-releases",
    url("http://dl.bintray.com/content/tpolecat/sbt-plugin-releases"))(
        Resolver.ivyStylePatterns)

addSbtPlugin("me.lessis"         % "bintray-sbt"  % "0.3.0")
addSbtPlugin("com.github.gseitz" % "sbt-release"  % "1.0.0")
addSbtPlugin("com.typesafe.sbt"  % "sbt-site"     % "0.8.1")
addSbtPlugin("com.typesafe.sbt"  % "sbt-ghpages"  % "0.5.3")
addSbtPlugin("org.tpolecat"      % "tut-plugin"   % "0.4.0")
addSbtPlugin("com.timushev.sbt"  % "sbt-updates"  % "0.1.8")
addSbtPlugin("com.eed3si9n"      % "sbt-assembly" % "0.11.2")

scalacOptions += "-deprecation"

