package com.codekeepersinc.kamonlogstash

import java.net.InetSocketAddress

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import akka.util.ByteString
import LogstashClient.CloseConnection
import akka.event.Logging

object LogstashClient {

  sealed trait LogstashClientMessage

  case object Connect extends LogstashClientMessage
  case object Connected extends LogstashClientMessage
  case object CloseConnection extends LogstashClientMessage

  def props(remote: InetSocketAddress, manager: ActorRef): Props = Props(new LogstashClient(remote, manager))

}

class LogstashClient(remote: InetSocketAddress, manager: ActorRef) extends Actor {

  import Tcp._
  import context.system

  val log = Logging(context.system, this)

  def logFailure(reason: Option[Throwable], message: Option[Any]) = {
    def msg = message.map(_ => " when processing [{}]").getOrElse("")
    reason match {
      case Some(r) =>
        log.error(r, s"Restarting due to [{}]$msg",
          r.getMessage, message.getOrElse(""))
      case None =>
        log.error(s"Restarting$msg", message.getOrElse(""))
    }

  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = logFailure(Option(reason), message)

  def receive: Receive = {
    case Connect => IO(Tcp) ! Connect(remote)
    case f @ CommandFailed(c: Connect) => {
      logFailure(f.cause, Some(f))
      context stop self
    }

    case c @ Connected(remote, local) =>
      manager ! LogstashClient.Connected
      val connection = sender()
      connection ! Register(self)
      context watch connection
      context become receiveOnConnected(connection)
  }

  def receiveOnConnected(connection: ActorRef): Receive = {
    case data: ByteString => connection ! Write(data)

    case f @ CommandFailed(w: Write) => {
      logFailure(f.cause, Some(f))
      context stop self
    }

    case CloseConnection => connection ! Close

    case c: ConnectionClosed => {
      log.debug("Closing connection")
      context stop self
    }

  }

  override def preStart(): Unit = self ! Connect
}
