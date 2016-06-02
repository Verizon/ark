#: ----------------------------------------------------------------------------
#: Copyright (C) 2016 Verizon.  All Rights Reserved.
#:
#:   Licensed under the Apache License, Version 2.0 (the "License");
#:   you may not use this file except in compliance with the License.
#:   You may obtain a copy of the License at
#:
#:       http://www.apache.org/licenses/LICENSE-2.0
#:
#:   Unless required by applicable law or agreed to in writing, software
#:   distributed under the License is distributed on an "AS IS" BASIS,
#:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#:   See the License for the specific language governing permissions and
#:   limitations under the License.
#:
#: ----------------------------------------------------------------------------
FROM ubuntu:14.04

RUN echo "deb http://repos.mesosphere.io/ubuntu/ trusty main" > /etc/apt/sources.list.d/mesosphere.list && \
  apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
  apt-get -y update && \
  apt-get -y install mesos=0.25.* && \
  apt-get clean && rm -rf /var/lib/apt/lists/*

VOLUME /opt/app

WORKDIR /opt/app

ENTRYPOINT java -jar example-assembly-*.jar
