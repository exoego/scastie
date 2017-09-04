package com.olegych.scastie.ensime

import com.olegych.scastie.api._
import com.olegych.scastie.sbt.Sbt
import com.olegych.scastie.util.ScastieFileUtil._
import com.olegych.scastie.util.ProcessUtils._
import com.olegych.scastie.util.TaskTimeout

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.Timeout

import org.ensime.api._
import org.ensime.sexp.formats.{
  CamelCaseToDashes,
  DefaultSexpProtocol,
  OptionAltFormat
}
import org.ensime.jerky.JerkyFormats._

import org.slf4j.LoggerFactory

import java.io.File._
import java.io._
import java.nio.file.{Files, Path, Paths, StandardOpenOption}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.io.Source._
import scala.util.{Failure, Success}

case object Heartbeat
case object EnsimeReady

class EnsimeRunner(system: ActorSystem,
                   dispatchActor: ActorRef,
                   sbtReloadTimeout: FiniteDuration)
    extends Actor {
  import spray.json._
  import system.dispatcher

  import EnsimeServerState._

  private case object IndexerReady

  private object serverState {
    private var state: EnsimeServerState = Initializing

    def apply(that: EnsimeServerState) = {
      println(s"--- $that ---")

      that match {
        case Unknown => ()
        case Initializing   => ()
        case CreatingConfig => ()
        case Connecting => {
          assert(
            state == CreatingConfig &&
              ensimeProcess.isDefined &&
              ensimeProcess.get.isAlive
          )
        }
        case Indexing => {
          assert(state == Connecting && ensimeWS.isDefined)
        }
        case Ready => {
          assert(state == Indexing)
        }
      }

      dispatchActor ! that

      if (state != that) {
        log.info(s"Server State: $state => $that")
      }
      state = that
    }

    def isReady = state == Ready
  }

  private val log = LoggerFactory.getLogger(getClass)

  implicit val materializer_ : ActorMaterializer = ActorMaterializer()
  implicit val timeout: Timeout = Timeout(5.seconds)

  private var ensimeProcess: Option[Process] = None
  private var ensimeWS: Option[ActorRef] = None
  private var hbRef: Option[Cancellable] = None

  private var nextId = 1
  private var requests = Map[Int, (ActorRef, Option[EnsimeTaskId])]()

  // config file generated by sbt which is currently used by ensime server
  private var currentConfig: Inputs = Inputs.default
  private val sbt = new Sbt(currentConfig, Seq("-Xms256m", "-Xmx512m"))

  private val codeFile: Path = sbt.sbtDir.resolve("src/main/scala/main.scala")

  def handleRPCResponse(id: Int, payload: EnsimeServerMessage): Unit = {
    requests.get(id) match {
      case Some((ref, maybeTaskId)) =>
        requests -= id

        def reply(response: EnsimeResponse): Unit = {
          maybeTaskId match {
            case Some(taskId) =>
              ref ! EnsimeTaskResponse(Some(response), taskId)
            case None => ref ! response
          }
        }

        def emptyReply(): Unit = {
          maybeTaskId match {
            case Some(taskId) =>
              ref ! EnsimeTaskResponse(None, taskId)
            case None => ()
          }
        }

        payload match {
          case CompletionInfoList(_, completionList) => {
            val response = AutoCompletionResponse(
              completionList
                .sortBy(-_.relevance)
                .map(ci => {
                  val (signature, resultType) = ci.typeInfo match {
                    case Some(ati: ArrowTypeInfo) =>
                      (ati.name, ati.resultType.name)
                    case Some(ti) => (ti.name, "")
                    case _        => ("", "")
                  }
                  Completion(ci.name, signature, resultType)
                })
            )
            log.debug(s"$id: Got ${response.completions.size} completions")

            if (!serverState.isReady) {
              println("Got warm up completions")
              response.completions.take(5).foreach(println)
              serverState(Indexing)
            }

            reply(response)
          }

          case symbolInfo: SymbolInfo => {
            log.info(s"Got symbol info: $symbolInfo")

            if (symbolInfo.`type`.name == "<none>")
              reply(TypeAtPointResponse(""))
            else if (symbolInfo.`type`.fullName.length <= 60)
              reply(TypeAtPointResponse(symbolInfo.`type`.fullName))
            else
              reply(TypeAtPointResponse(symbolInfo.`type`.name))
          }

          // used as keepalive
          case _: ConnectionInfo => {
            ()
          }

          case FalseResponse => {
            emptyReply()
            println("-- FalseResponse --")
          }

          case x => {
            emptyReply()
            log.info(s"Got unexpected response from ensime : {}", x)
          }
        }

      case _ =>
        log.info(s"Got response without requester $id -> $payload")
    }
  }

  def sendToEnsime(rpcRequest: RpcRequest,
                   sender: ActorRef,
                   taskId: Option[EnsimeTaskId] = None): Unit = {

    requests += (nextId -> (sender, taskId))
    val env = RpcRequestEnvelope(rpcRequest, nextId)
    nextId += 1

    log.debug(s"Sending $env")
    val json = env.toJson.prettyPrint
    ensimeWS match {
      case Some(ws) => ws ! TextMessage.Strict(json)
      case None     => log.error("Trying to use not initialized WebSocket")
    }
  }

  private def connectToEnsime(uri: String) = {
    serverState(Connecting)

    log.info(s"Connecting to $uri")

    val req = WebSocketRequest(uri, subprotocol = Some("jerky"))
    val webSocketFlow = Http()(system).webSocketClientFlow(req)

    val messageSource: Source[Message, ActorRef] =
      Source
        .actorRef[TextMessage.Strict](bufferSize = 10, OverflowStrategy.fail)

    def handleIncomingMessage(message: String) = {
      val env = message.parseJson.convertTo[RpcResponseEnvelope]
      env.callId match {
        case Some(id) => handleRPCResponse(id, env.payload)
        case None     => ()
      }
    }

    val messageSink: Sink[Message, NotUsed] =
      Flow[Message]
        .map {
          case msg: TextMessage.Strict =>
            handleIncomingMessage(msg.text)
          case msgStream: TextMessage.Streamed =>
            msgStream.textStream.runFold("")(_ + _).onComplete {
              case Success(msg) => handleIncomingMessage(msg)
              case Failure(e) =>
                log.info(s"Couldn't process incoming text stream. $e")
            }
          case _ =>
            log.info(
              "Got unsupported ws response message type from ensime-server"
            )
        }
        .to(Sink.ignore)

    val ((ws, upgradeResponse), _) =
      messageSource
        .viaMat(webSocketFlow)(Keep.both)
        .toMat(messageSink)(Keep.both)
        .run()

    upgradeResponse.flatMap { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Future.successful(Done)
      } else {
        throw new RuntimeException(
          s"Connection failed: ${upgrade.response.status}"
        )
      }
    }

    ensimeWS = Some(ws)

    sendToEnsime(ConnectionInfoReq, self)
    hbRef = Some(
      context.system.scheduler
        .schedule(30.seconds, 30.seconds, self, Heartbeat)
    )
  }

  override def preStart(): Unit = {
    try {
      startEnsimeServer(withConfig = Inputs.default, force = true)
    } catch {
      case e: FileNotFoundException => log.error(e.getMessage)
    }
    super.preStart()
  }

  private def startEnsimeServer(withConfig: Inputs,
                                force: Boolean = false,
                                configFailed: Boolean = false): Unit = {
    if (needsReload(withConfig) || force) {
      serverState(CreatingConfig)

      TaskTimeout(
        duration = sbtReloadTimeout,
        task = {

          log.info("ensimeConfig Start")

          var afterDone = false

          sbt.eval(
            "ensimeConfig",
            withConfig,
            (line, done, sbtError, reload) => {
              log.info(
                s"line: $line , done: $done , sbtError: $sbtError , reload: $reload"
              )
              if (done && !reload) {

                // make sure we only start once
                if (!afterDone) {
                  currentConfig = withConfig
                  log.info("ensimeConfig Done")
                  startEnsimeServerAfterConfig()
                  log.info("After startEnsimeServerAfterConfig")
                }

                afterDone = true
              }
            },
            reload = false
          )
        },
        onTimeout = {
          log.info("ensimeConfig timeout, Trying again: ${!configFailed}")

          if (!configFailed) {
            startEnsimeServer(Inputs.default, force, configFailed = true)
          }
        }
      )

      log.info("ensimeConfig Done")
    } else {
      startEnsimeServerAfterConfig()
    }
  }

  private def startEnsimeServerAfterConfig(): Unit = {
    log.info("startEnsimeServerAfterConfig")

    val sbtDir = sbt.sbtDir
    val ensimeConfigFile = sbtDir.resolve(".ensime")
    val ensimeCacheDir = sbtDir.resolve(".ensime_cache")
    Files.createDirectories(ensimeCacheDir)

    val httpPortFile = ensimeCacheDir.resolve("http")
    Files.deleteIfExists(httpPortFile)

    log.info("Form classpath using .ensime file")

    val ensimeConf = slurp(ensimeConfigFile)
    log.info("Path to ensimeConfigFile: " + ensimeConfigFile)
    assert(ensimeConf.isDefined, "ensime config does not exist")

    case class EnsimeClasspathConfig(
        ensimeServerJars: List[String],
        scalaCompilerJars: List[String]
    )

    object EnsimeConfProtocol
        extends DefaultSexpProtocol
        with OptionAltFormat
        with CamelCaseToDashes
    import EnsimeConfProtocol._
    import org.ensime.sexp._

    val parsedEnsimeConfig =
      ensimeConf.get.parseSexp.convertTo[EnsimeClasspathConfig]

    val classpathItems =
      parsedEnsimeConfig.ensimeServerJars ++
        parsedEnsimeConfig.scalaCompilerJars

    val classpath = classpathItems.mkString(pathSeparatorChar.toString)

    log.info("Starting Ensime server")

    if (ensimeProcess.isDefined) {
      throw new Exception("process already started")
    }

    ensimeProcess = Some(
      new ProcessBuilder(
        "java",
        "-Xms512M",
        "-Xmx1200M",
        "-XX:MaxDirectMemorySize=512M",
        "-Densime.config=" + ensimeConfigFile,
        "-classpath",
        classpath,
        "-Densime.explode.on.disconnect=true",
        "org.ensime.server.Server"
      ).directory(sbtDir.toFile).start()
    )

    val pid = getPid(ensimeProcess.get)

    log.info(s"Starting Ensime server, pid: $pid")

    val stdout = ensimeProcess.get.getInputStream
    streamLogger(stdout)
    val stderr = ensimeProcess.get.getErrorStream
    streamLogger(stderr)

    connectToEnsime(
      f"ws://127.0.0.1:${waitForAndReadPort(httpPortFile)}/websocket"
    )

    log.info("Warming up Ensime...")
    sendToEnsime(
      CompletionsReq(
        fileInfo = SourceFileInfo(RawFile(new File(codeFile.toString).toPath),
                                  Some(Inputs.defaultCode)),
        point = 2,
        maxResults = 2000,
        caseSens = false,
        reload = false
      ),
      self
    )
  }

  override def postStop(): Unit = {
    killEnsimeServer()
    sbt.kill()
    super.postStop()
  }

  def streamLogger(inputStream: InputStream): Unit = {
    Future {
      val is = new BufferedReader(new InputStreamReader(inputStream))
      var line = is.readLine()
      while (line != null) {
        if(line.contains("IndexerReadyEvent")) {
          self ! IndexerReady
        }

        if (!line.contains("ConnectionInfo") &&
            !line.contains("INFO") &&
            !line.contains("DEBUG")) {
          println(line)
        } else {
          print("*")
        }

        line = is.readLine()
      }
    }
    ()
  }

  private def killEnsimeServer(): Unit = {
    hbRef.foreach(_.cancel())

    ensimeProcess.foreach { process =>
      val pid = getPid(process)
      log.info("Killing Ensime server: " + pid)
      kill(process)
      log.info("Ensime server Killed")
    }

    ensimeProcess = None
    ensimeWS = None
    serverState(Initializing)
  }

  private def restartEnsimeServer(config: Inputs): Unit = {
    killEnsimeServer()
    startEnsimeServer(withConfig = config)
  }

  override def receive: Receive = {
    case EnsimeTaskRequest(
        TypeAtPointRequest(EnsimeRequestInfo(inputs, position)),
        taskId
        ) => {

      if (inputs.hasEnsimeSupport) {
        log.info("TypeAtPoint request at EnsimeActor")
        processRequest(
          taskId,
          sender,
          inputs,
          position,
          (code: String, pos: Int) => {
            SymbolAtPointReq(
              file = Right(
                SourceFileInfo(
                  RawFile(new File(codeFile.toString).toPath),
                  Some(code)
                )
              ),
              point = pos
            )
          }
        )
      } else {
        sender ! EnsimeTaskResponse(None, taskId)
      }
    }

    case EnsimeTaskRequest(
        AutoCompletionRequest(EnsimeRequestInfo(inputs, position)),
        taskId
        ) => {

      if (inputs.hasEnsimeSupport) {
        log.info("Completion request at EnsimeActor")
        processRequest(
          taskId,
          sender,
          inputs,
          position,
          (code: String, pos: Int) => {
            CompletionsReq(
              fileInfo =
                SourceFileInfo(RawFile(new File(codeFile.toString).toPath),
                               Some(code)),
              point = pos,
              maxResults = 100,
              caseSens = false,
              reload = false
            )
          }
        )
      } else {
        sender ! EnsimeTaskResponse(None, taskId)
      }
    }

    case EnsimeTaskRequest(UpdateEnsimeConfigRequest(inputs), taskId) => {
      log.info("UpdateEnsimeConfig request at EnsimeActor")

      val reloads = inputs.hasEnsimeSupport && needsReload(inputs)

      sender ! EnsimeTaskResponse(Some(EnsimeConfigUpdate(reloads)), taskId)

      if (reloads) {
        restartEnsimeServer(inputs)
      }
    }

    case IndexerReady => {
      serverState(Ready)
    }

    case Heartbeat => {
      sendToEnsime(ConnectionInfoReq, self)
    }

    case x => {
      log.info(s"Got $x at EnsimeActor")
    }
  }

  private def needsReload(inputs: Inputs) = {
    currentConfig.needsReload(inputs)
  }

  private def processRequest(
      taskId: EnsimeTaskId,
      sender: ActorRef,
      inputs: Inputs,
      position: Int,
      rpcRequestFun: (String, Int) => RpcRequest
  ): Unit = {
    val (code, pos) = if (inputs.worksheetMode) {
      val prelude = "object Main { "
      (s"${prelude}${inputs.code} }", position + prelude.length)
    } else {
      (inputs.code, position)
    }

    if (serverState.isReady) {
      if (needsReload(inputs)) {
        sender ! EnsimeTaskResponse(None, taskId)
        restartEnsimeServer(inputs)
      } else {
        sendToEnsime(rpcRequestFun(code, pos), sender, Some(taskId))
      }
    } else {
      sender ! EnsimeTaskResponse(None, taskId)
      
      log.info(
        s"Not ready to process request $taskId; currently: $serverState"
      )
    }
  }

  private def waitForAndReadPort(path: Path): Int = {
    var count = 0
    var res: Option[Int] = None
    val file = path.toFile
    log.info(s"Trying to read port file at: $path")

    while (count < 30 && res.isEmpty) {
      if (file.exists) {
        val handler = fromFile(file)
        val contents = fromFile(file).mkString
        handler.close()

        res = Some(Integer.parseInt(contents.trim))
      } else {
        Thread.sleep(1000)
      }
      count += 1
    }
    res match {
      case Some(p) =>
        p
      case None =>
        throw new IllegalStateException(s"Port file $file not available")
    }
  }
}
