package com.olegych.scastie.balancer

import com.olegych.scastie.api._
import org.scalatest.{Assertion, FunSuite}

import scala.collection.immutable.Queue
import scala.concurrent.duration._

case class TestTaskId(id: Int) extends TaskId {
  def cost: Int = 1
}

case class TestServerRef(id: Int)
case class TestState(state: String, ready: Boolean = true) extends ServerState {
  def isReady: Boolean = ready
}

trait LoadBalancerTestUtils extends FunSuite with TestUtils {
  type TestServer0 = Server[TestTaskId, TestServerRef, TestState]

  object TestLoadBalancer {
    def apply(
        servers: Vector[TestServer0],
        history: History,
    ): TestLoadBalancer0 = {
      LoadBalancer(
        servers = servers,
        history = history,
        taskCost = 2.seconds,
        reloadCost = 100.seconds,
      )
    }
  }
  type TestLoadBalancer0 =
    LoadBalancer[TestTaskId, TestServerRef, TestState]

  object TestServer {
    def apply(
        ref: TestServerRef,
        lastConfig: Inputs,
        mailbox: Queue[Task[TestTaskId]] = Queue(),
        state: TestState = TestState("default-state")
    ): TestServer0 = {
      Server(ref, lastConfig, mailbox, state)
    }
  }

  @transient private var taskId = 1000
  def add(balancer: TestLoadBalancer0, config: Inputs): TestLoadBalancer0 = synchronized {
    val (_, balancer0) = balancer.add(Task(config, nextIp, TestTaskId(taskId))).get
    taskId += 1
    balancer0
  }

  def assertConfigs(balancer: TestLoadBalancer0)(columns: Seq[String]*): Assertion = {
    assert(balancer.servers.map(_.currentConfig).toSet == columns.flatten.map(i => Inputs.default.copy(code = i.toString)).toSet)
  }

  @transient private var serverId = 0
  def server(
      c: String,
      mailbox: Queue[Task[TestTaskId]] = Queue(),
      state: TestState = TestState("default-state")
  ): TestServer0 = synchronized {
    val t = Server(TestServerRef(serverId), config(c), mailbox, state)
    serverId += 1
    t
  }

  def servers(columns: Seq[String]*): Vector[TestServer0] = {
    columns.to[Vector].flatten.map(c => server(c))
  }

  @transient private var currentIp = 0
  def nextIp: Ip = synchronized {
    val t = Ip("ip" + currentIp)
    currentIp += 1
    t
  }

  def server(v: Int): TestServerRef = TestServerRef(v)

  def config(code: String) = Inputs.default.copy(code = code)
  def sbtConfig(sbtConfig: String) = Inputs.default.copy(sbtConfigExtra = sbtConfig)

  def history(columns: Seq[String]*): History = {
    val records =
      columns.to[Vector].flatten.map(i => Record(Inputs.default.copy(code = i.toString), nextIp)).reverse

    History(Queue(records: _*), size = 20)
  }
}
