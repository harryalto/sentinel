package nl.gideondk.sentinel

import java.net.InetSocketAddress

import akka.actor._
import akka.io._
import akka.io.Tcp._
import akka.util.{ Timeout, ByteString }

import scala.concurrent.{ Future, Promise, ExecutionContext }
import scala.util.Random

import akka.pattern.ask

trait Server[Cmd, Evt] {
  def actor: ActorRef

  def ?**(command: Cmd)(implicit context: ExecutionContext): Future[List[Evt]] = askAll(command)

  def ?*(command: Cmd)(implicit context: ExecutionContext): Future[List[Evt]] = askAllHosts(command)

  def ?(command: Cmd)(implicit context: ExecutionContext): Future[Evt] = askAny(command)

  def askAll(command: Cmd)(implicit context: ExecutionContext): Future[List[Evt]] = {
    val promise = Promise[List[Evt]]()
    actor ! ServerCommand.AskAll(command, promise)
    promise.future
  }

  def askAllHosts(command: Cmd)(implicit context: ExecutionContext): Future[List[Evt]] = {
    val promise = Promise[List[Evt]]()
    actor ! ServerCommand.AskAllHosts(command, promise)
    promise.future
  }

  def askAny(command: Cmd)(implicit context: ExecutionContext): Future[Evt] = {
    val promise = Promise[Evt]()
    actor ! ServerCommand.AskAny(command, promise)
    promise.future
  }

  def connectedSockets(implicit timeout: Timeout): Future[Int] = {
    (actor ? ServerMetric.ConnectedSockets).mapTo[Int]
  }

  def connectedHosts(implicit timeout: Timeout): Future[Int] = {
    (actor ? ServerMetric.ConnectedHosts).mapTo[Int]
  }
}

class ServerCore[Cmd, Evt](port: Int, description: String, stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString],
                           resolver: Resolver[Evt, Cmd], workerDescription: String = "Sentinel Client Worker")(lowBytes: Long, highBytes: Long, maxBufferSize: Long) extends Actor with ActorLogging {

  import context.dispatcher

  def wrapAtenna(a: ActorRef) = new Client[Cmd, Evt] {
    val actor = a
  }

  val tcp = akka.io.IO(Tcp)(context.system)
  val address = new InetSocketAddress(port)

  var connections = Map[String, List[ActorRef]]()

  override def preStart = {
    tcp ! Bind(self, address)
  }

  def receiveCommands: Receive = {
    case x: ServerCommand.AskAll[Cmd, Evt] ⇒
      if (connections.values.toList.length > 0) {
        val futures = Future.sequence(connections.values.toList.flatten.map(wrapAtenna).map(_ ? x.payload))
        x.promise.completeWith(futures)
      } else x.promise.failure(new Exception("No clients connected"))

    case x: ServerCommand.AskAllHosts[Cmd, Evt] ⇒
      if (connections.values.toList.length > 0) {
        val futures = Future.sequence(connections.values.toList.map(x ⇒ Random.shuffle(x.toList).head).map(wrapAtenna).map(_ ? x.payload))
        x.promise.completeWith(futures)
      } else x.promise.failure(new Exception("No clients connected"))

    case x: ServerCommand.AskAny[Cmd, Evt] ⇒
      if (connections.values.toList.length > 0) {
        val future = (wrapAtenna(Random.shuffle(connections.values.toList.flatten).head) ? x.payload)
        x.promise.completeWith(future)
      } else x.promise.failure(new Exception("No clients connected"))

    case ServerMetric.ConnectedSockets ⇒
      sender ! connections.values.flatten.toList.length

    case ServerMetric.ConnectedHosts ⇒
      sender ! connections.keys.toList.length
  }

  def receive = receiveCommands orElse {
    case x: Terminated ⇒
      val antenna = x.getActor
      connections = connections.foldLeft(Map[String, List[ActorRef]]()) {
        case (c, i) ⇒
          i._2.contains(antenna) match {
            case true  ⇒ if (i._2.length == 1) c else c + (i._1 -> i._2.filter(_ != antenna))
            case false ⇒ c + i
          }
      }

    case Bound ⇒
      log.debug(description + " bound to " + address)

    case CommandFailed(cmd) ⇒
      cmd match {
        case x: Bind ⇒
          log.error(description + " failed to bind to " + address)
      }

    case req @ Connected(remoteAddr, localAddr) ⇒
      val init =
        TcpPipelineHandler.withLogger(log,
          stages >>
            new TcpReadWriteAdapter >>
            new BackpressureBuffer(lowBytes, highBytes, maxBufferSize))

      val connection = sender

      val antenna = context.actorOf(Props(new Antenna(init, resolver)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"))
      context.watch(antenna)

      val currentAtennas = connections.get(remoteAddr.getHostName).getOrElse(List[ActorRef]())
      connections = connections + (remoteAddr.getHostName -> (currentAtennas ++ List(antenna)))

      val tcpHandler = context.actorOf(TcpPipelineHandler.props(init, connection, antenna).withDeploy(Deploy.local))

      antenna ! Management.RegisterTcpHandler(tcpHandler)
      connection ! Tcp.Register(tcpHandler)
  }
}

object Server {
  def apply[Evt, Cmd](serverPort: Int, resolver: Resolver[Evt, Cmd], description: String = "Sentinel Server", stages: ⇒ PipelineStage[PipelineContext, Cmd, ByteString, Evt, ByteString], lowBytes: Long = 100L, highBytes: Long = 50 * 1024L, maxBufferSize: Long = 1000L * 1024L)(implicit system: ActorSystem) = {
    new Server[Evt, Cmd] {
      val actor = system.actorOf(Props(new ServerCore(serverPort, description, stages, resolver)(lowBytes, highBytes, maxBufferSize)).withDispatcher("nl.gideondk.sentinel.sentinel-dispatcher"), name = "sentinel-server-" + java.util.UUID.randomUUID.toString)
    }
  }
}
