# Ark

![image](docs/img/logo.png)

[![Build Status](https://travis-ci.org/Verizon/ark.svg?branch=master)](https://travis-ci.org/Verizon/ark)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.verizon.ark/core_2.10/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.verizon.ark/core_2.10)
[![codecov](https://codecov.io/gh/Verizon/ark/branch/master/graph/badge.svg)](https://codecov.io/gh/Verizon/ark)

This library provides a functional scala implementation of `org.apache.mesos.Scheduler` interface provided by [Mesos java API](http://mesos.apache.org/api/latest/java/).

The goal of this library is to ease development of mesos schedulers by providing out-of-the-box implementations of common operational requirements of a framework, allowing developers to focus on domain logic implementation of task state transitions.

Features:

   * Pure functional implementation of mesos scheduler tasks state.
   * Scalaz stream to queue all messages sent to the framework (from mesos master or custom user defined messages) to be processed one at a time making it completely thread safe.
   * Recurring reconcialiation based on [Mesos Reconciliation Algorithm](http://mesos.apache.org/documentation/latest/reconciliation/).
   * *TODO:* Re-registration on mesos master failures.
   * *TODO:* High-Availability mode and leader election.

From the current state of the project there is a clear path to implement missing features above by enhancing `oncue.mesos.Scheduler.processMessage` function.


## Messages

The core of this Mesos Scheduler implementation is handled by a scalaz async message queue. When Mesos calls any of the functions provided by the `Scheduler` interface, the scheduler creates one or many `oncue.mesos.MesosMessage` and enqueues them in the scalaz stream.

```scala
sealed trait MesosMessage { def driver: org.apache.mesos.SchedulerDriver }
```

The main scalaz stream is created inside `oncue.mesos.Scheduler` to handle calls from Mesos to the Scheduler interface. Users can provide any number of `scalaz.stream.Process[scalaz.concurrent.Task, CustomMessage]` when initializing the `Scheduler`. These custom streams get merged into the internal scalaz stream. This way the user can trigger any `CustomMesssage` to the scheduler which is handled by the same `processMessage` function that handles messages from Mesos.

```scala
trait CustomMessage extends MesosMessage
```

## Reconciliation

Mesos has very good documentation on how to implement the [Reconciliation Algorithm](http://mesos.apache.org/documentation/latest/reconciliation/), since most frameworks need to perform reconciliation this was the first feature to address in a common Mesos scheduler library.

Reconciliation is triggered by sending a `ReconcileMessage` to the stream:

```scala
case class ReconcileMessage(override val driver: SchedulerDriver) extends CustomMessage
```

`oncue.mesos.Scheduler` companion object provides a convenient function to initialize a timed reconciliation stream:

```scala
def reconcileProcess(driver: SchedulerDriver, reconcileInterval: FiniteDuration): Process[Task, ReconcileMessage] = {
  time.awakeEvery(reconcileInterval)(defaultExecutor, timeOutScheduler)
    .map(_ => ReconcileMessage(driver))
}
```

The user can create a reconcile process by calling the function above and passing it to the scheduler `init` function, this will trigger reconcialiation every `reconcileInterval` and all offers will be declined until reconciliation is over.

```scala
val reconciliationInterval = 1 hour
val customStreams = Seq( Scheduler.reconcileProcess(driver, reconciliationInterval) )
scheduler.init(state, driver, customStreams).run
```

*TODO:* The wait time to reconcile all tasks is currently fixed, Mesos recommends to use truncated exponential back off to "avoid a snowball effect in the case of the driver or master being backed up".


## Usage

A full implementation of Mesos Scheduler would be required to implement `oncue.mesos.SchedulerState` and `oncue.mesos.SchedulerStateManager` traits and run the scheduler like this:

```scala
  // implement state and state manager
  case class MyState( ... ) extends SchedulerState
  class MyStateManager extends SchedulerState[MyState] { ... }

  // initialize state and state manager
  val initialState = MyState( ... )
  val stateManager = new MyStateManager( ... )

  // define framework info
  val frameworkInfo = Protos.FrameworkInfo.newBuilder
    .setName("my-framework")
    .setOtherFrameworkattributes( ... )
    .build

  // initialize scheduler and mesos driver
  val scheduler = new oncue.mesos.Scheduler(stateManager)
  val driver = new org.apache.mesos.MesosSchedulerDriver(scheduler, frameworkInfo, mesosMaster)

  // shutdown scheduler on exit
  sys addShutdownHook {
    scheduler.shutdown(driver)
  }

  // Seq[Process[Task,CustomMessage]] pass custom state mutation messages
  // Scheduler.reconcileProcess triggers reconciliation every "reconciliationInterval"
  val reconciliationInterval = 1 hour
  val customStreams = Seq(Scheduler.reconcileProcess(driver, reconciliationInterval))

  // run scheduler (blocking)
  scheduler.init(initialState, driver, customStreams).run
```

### Example

The provided example implementation creates a scheduler that triggers the provided task on every slave in the cluster.
This example also uses [http4s](http://http4s.org/) to set up REST endpoints to query current scheduler state by
sending custom messages to the queue. User can query scheduler info and add or remove slaves from a blacklist.

Running example module on a local mesos cluster with 2 slaves using docker-machine on mac (see
https://github.com/mesosphere/docker-containers/tree/master/mesos):

1. Run ZK:
   
   ```bash
   docker run -d --net=host netflixoss/exhibitor:1.5.2
   ```
   
1. Run master:
   
   ```bash
    docker run -d --net=host \
      -e LIBPROCESS_IP=$(docker-machine ip) \
      -e HOSTNAME=$(docker-machine ip) \
      -e MESOS_PORT=5050 \
      -e MESOS_ZK=zk://127.0.0.1:2181/mesos \
      -e MESOS_QUORUM=1 \
      -e MESOS_REGISTRY=in_memory \
      -e MESOS_LOG_DIR=/var/log/mesos \
      -e MESOS_WORK_DIR=/var/tmp/mesos \
      -v "$(pwd)/log/mesos:/var/log/mesos" \
      -v "$(pwd)/tmp/mesos:/var/tmp/mesos" \
      mesosphere/mesos-master:0.25.0-0.2.70.ubuntu1404
   ```
   
1. Run slaves, notice `MESOS_PORT` and mount points change for `/var/log/mesos` and `/var/tmp/mesos`:
   
   ```bash
   docker run -d --net=host --privileged \
      -e LIBPROCESS_IP=$(docker-machine ip) \
      -e HOSTNAME=$(docker-machine ip)  \
      -e MESOS_PORT=5051 \
      -e MESOS_MASTER=zk://127.0.0.1:2181/mesos \
      -e MESOS_SWITCH_USER=0 \
      -e MESOS_CONTAINERIZERS=docker,mesos \
      -e MESOS_LOG_DIR=/var/log/mesos \
      -e MESOS_WORK_DIR=/var/tmp/mesos \
      -v "$(pwd)/log/mesos1:/var/log/mesos" \
      -v "$(pwd)/tmp/mesos1:/var/tmp/mesos" \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -v /cgroup:/cgroup \
      -v /sys:/sys \
      -v /usr/local/bin/docker:/usr/local/bin/docker \
      mesosphere/mesos-slave:0.25.0-0.2.70.ubuntu1404

   docker run -d --net=host --privileged \
      -e LIBPROCESS_IP=$(docker-machine ip) \
      -e HOSTNAME=$(docker-machine ip)  \
      -e MESOS_PORT=5052 \
      -e MESOS_MASTER=zk://127.0.0.1:2181/mesos \
      -e MESOS_SWITCH_USER=0 \
      -e MESOS_CONTAINERIZERS=docker,mesos \
      -e MESOS_LOG_DIR=/var/log/mesos \
      -e MESOS_WORK_DIR=/var/tmp/mesos \
      -v "$(pwd)/log/mesos2:/var/log/mesos" \
      -v "$(pwd)/tmp/mesos2:/var/tmp/mesos" \
      -v /var/run/docker.sock:/var/run/docker.sock \
      -v /cgroup:/cgroup \
      -v /sys:/sys \
      -v /usr/local/bin/docker:/usr/local/bin/docker \
      mesosphere/mesos-slave:0.25.0-0.2.70.ubuntu1404
   ```
   
1. Build scheduler assembly jar

   ```bash
   sbt "project example" assembly
   ```
1. Build scheduler container from example/Dockerfile:
   
   ```bash
   docker build -t mysched example/
   ```
   
1. Run scheduler container interactively:
   
   ```bash
   docker run --rm --net=host -it \
      -e LIBPROCESS_IP=$(docker-machine ip) \
      -v $(pwd)/example/target/scala-2.10:/opt/app \
      mysched
   ```


