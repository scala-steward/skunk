// Copyright (c) 2018-2020 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk.net

import cats.effect._
import cats.syntax.all._
import fs2.concurrent.InspectableQueue
import scodec.codecs._
import scodec.interop.cats._
import skunk.net.message.{ Sync => _, _ }
import skunk.util.Origin
import scala.concurrent.duration.FiniteDuration
import fs2.io.tcp.SocketGroup

/** A higher-level `BitVectorSocket` that speaks in terms of `Message`. */
trait MessageSocket[F[_]] {

  /**
   * Receive the next `BackendMessage`, or raise an exception if EOF is reached before a complete
   * message arrives.
   */
  def receive: F[BackendMessage]

  /** Send the specified message. */
  def send(message: FrontendMessage): F[Unit]

  /** Destructively read the last `n` messages from the circular buffer. */
  def history(max: Int): F[List[Either[Any, Any]]]

  def expect[B](f: PartialFunction[BackendMessage, B])(implicit or: Origin): F[B]
  def flatExpect[B](f: PartialFunction[BackendMessage, F[B]])(implicit or: Origin): F[B]

}

object MessageSocket {

  def fromBitVectorSocket[F[_]: Concurrent](
    bvs: BitVectorSocket[F],
    debug: Boolean
  ): F[MessageSocket[F]] =
    InspectableQueue.circularBuffer[F, Either[Any, Any]](10).map { cb =>
      new AbstractMessageSocket[F] with MessageSocket[F] {

        /**
        * Messages are prefixed with a 5-byte header consisting of a tag (byte) and a length (int32,
        * total including self but not including the tag) in network order.
        */
        val receiveImpl: F[BackendMessage] = {
          val header = (byte.asDecoder, int32.asDecoder).tupled
          bvs.read(5).flatMap { bits =>
            val (tag, len) = header.decodeValue(bits).require
            val decoder    = BackendMessage.decoder(tag)
            bvs.read(len - 4).map(decoder.decodeValue(_).require)
          } .onError {
            case t => Sync[F].delay(println(s" ← ${Console.RED}${t.getMessage}${Console.RESET}")).whenA(debug)
          }
        }

        override val receive: F[BackendMessage] =
          for {
            msg <- receiveImpl
            _   <- cb.enqueue1(Right(msg))
            _   <- Sync[F].delay(println(s" ← ${Console.GREEN}$msg${Console.RESET}")).whenA(debug)
          } yield msg

        override def send(message: FrontendMessage): F[Unit] =
          for {
            _ <- Sync[F].delay(println(s" → ${Console.YELLOW}$message${Console.RESET}")).whenA(debug)
            _ <- bvs.write(message.encode)
            _ <- cb.enqueue1(Left(message))
          } yield ()

        override def history(max: Int): F[List[Either[Any, Any]]] =
          cb.dequeueChunk1(max: Int).map(_.toList)

      }
    }

  def apply[F[_]: Concurrent: ContextShift](
    host:         String,
    port:         Int,
    debug:        Boolean,
    readTimeout:  FiniteDuration,
    writeTimeout: FiniteDuration,
    sg:           SocketGroup,
    sslOptions:   Option[SSLNegotiation.Options[F]],
  ): Resource[F, MessageSocket[F]] =
    for {
      bvs <- BitVectorSocket(host, port, readTimeout, writeTimeout, sg, sslOptions)
      ms  <- Resource.eval(fromBitVectorSocket(bvs, debug))
    } yield ms


}
