package sttp.client4.http4s

import java.io.InputStream
import java.nio.charset.Charset
import cats.effect.concurrent.MVar
import cats.effect._
import cats.implicits._
import cats.effect.implicits._
import fs2.{Chunk, Stream, Pull}
import org.http4s.{EntityBody, Request => Http4sRequest, Status}
import org.http4s
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.Client
import org.http4s.headers.`Content-Encoding`
import org.typelevel.ci.CIString
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.internal.{BodyFromResponseAs, IOBufferSize, SttpFile}
import sttp.model._
import sttp.monad.MonadError
import sttp.client4.testing.StreamBackendStub
import sttp.client4.ws.{GotAWebSocketException, NotAWebSocketException}
import sttp.client4._
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.client4.compression.Compressor
import sttp.client4.compression.CompressionHandlers
import sttp.client4.impl.fs2.GZipFs2Decompressor
import sttp.client4.impl.fs2.DeflateFs2Decompressor
import sttp.client4.compression.Decompressor
import sttp.capabilities.StreamMaxLengthExceededException

import scala.concurrent.ExecutionContext

class Http4sBackend[F[_]: ConcurrentEffect: ContextShift](
    client: Client[F],
    blocker: Blocker,
    customizeRequest: Http4sRequest[F] => Http4sRequest[F],
    compressionHandlers: CompressionHandlers[Fs2Streams[F], EntityBody[F]]
) extends StreamBackend[F, Fs2Streams[F]] {
  type R = Fs2Streams[F] with sttp.capabilities.Effect[F]

  override def send[T](r: GenericRequest[T, R]): F[Response[T]] =
    adjustExceptions(r) {
      val (body, contentLength) = Compressor.compressIfNeeded(r, compressionHandlers.compressors)
      val (entity, extraHeaders) = bodyToHttp4s(body, contentLength)
      val headers =
        http4s.Headers {
          val nonClHeaders = r.headers
            .filterNot(_.is(HeaderNames.ContentLength))
            .map(h => http4s.Header.Raw(CIString(h.name), h.value))
            .toList

          val clHeader = contentLength
            .map(cl => http4s.Header.Raw(CIString(HeaderNames.ContentLength), cl.toString))

          nonClHeaders ++ clHeader
        } ++ extraHeaders

      val request = r.httpVersion match {
        case Some(version) =>
          Http4sRequest(
            method = methodToHttp4s(r.method),
            uri = http4s.Uri.unsafeFromString(r.uri.toString),
            headers = headers,
            body = entity.body,
            httpVersion = versionToHttp4s(version)
          )
        case None =>
          Http4sRequest(
            method = methodToHttp4s(r.method),
            uri = http4s.Uri.unsafeFromString(r.uri.toString),
            headers = headers,
            body = entity.body
          )
      }

      // see adr0001
      MVar.empty[F, Unit].flatMap { responseBodyCompleteVar =>
        MVar.empty[F, Either[Throwable, Response[T]]].flatMap { responseVar =>
          val sendRequest = client
            .run(customizeRequest(request))
            .use { response =>
              val code = StatusCode.unsafeApply(response.status.code)
              val headers = response.headers.headers.map(h => Header(h.name.toString, h.value))
              val statusText = response.status.reason
              val responseMetadata = ResponseMetadata(code, statusText, headers)

              val limitedResponse: org.http4s.Response[F] =
                r.options.maxResponseBodyLength
                  .fold(response)(limit => response.copy(body = limitBytes(response.body, limit)))

              val signalBodyComplete = responseBodyCompleteVar.tryPut(()).map(_ => ())
              val body =
                bodyFromResponseAs(signalBodyComplete)(
                  r.response,
                  responseMetadata,
                  Left(
                    onFinalizeSignal(
                      addOnBodyReceivedCallback(
                        decompressResponseBodyIfNotHead(r.method, limitedResponse, r.autoDecompressionEnabled),
                        () => r.options.onBodyReceived(responseMetadata)
                      ),
                      signalBodyComplete
                    )
                  )
                )

              body
                .map(b => Response(b, code, statusText, headers, Nil, r.onlyMetadata))
                .flatMap(r => responseVar.put(Right(r)))
                .flatMap(_ => responseBodyCompleteVar.take)
            }
            .recoverWith { case t: Throwable => responseVar.put(Left(t)) }

          sendRequest.start >> responseVar.take.flatMap {
            case Left(t)  => implicitly[cats.ApplicativeError[F, Throwable]].raiseError(t)
            case Right(r) => r.pure[F]
          }
        }
      }
    }

  private def methodToHttp4s(m: Method): http4s.Method =
    m match {
      case Method.GET     => http4s.Method.GET
      case Method.HEAD    => http4s.Method.HEAD
      case Method.POST    => http4s.Method.POST
      case Method.PUT     => http4s.Method.PUT
      case Method.DELETE  => http4s.Method.DELETE
      case Method.OPTIONS => http4s.Method.OPTIONS
      case Method.PATCH   => http4s.Method.PATCH
      case Method.CONNECT => http4s.Method.CONNECT
      case Method.TRACE   => http4s.Method.TRACE
      case _              => http4s.Method.fromString(m.method).right.get
    }

  private def versionToHttp4s(version: HttpVersion): http4s.HttpVersion =
    version match {
      case HttpVersion.HTTP_1   => http4s.HttpVersion.`HTTP/1.0`
      case HttpVersion.HTTP_1_1 => http4s.HttpVersion.`HTTP/1.1`
      case HttpVersion.HTTP_2   => http4s.HttpVersion.`HTTP/2`
      case HttpVersion.HTTP_3   => http4s.HttpVersion.`HTTP/3`
    }
  private def charsetToHttp4s(encoding: String) = http4s.Charset.fromNioCharset(Charset.forName(encoding))

  private def basicBodyToHttp4s(body: BasicBodyPart): http4s.Entity[F] =
    body match {
      case StringBody(b, encoding, _) =>
        http4s.EntityEncoder.stringEncoder(charsetToHttp4s(encoding)).toEntity(b)

      case ByteArrayBody(b, _) =>
        http4s.EntityEncoder.byteArrayEncoder.toEntity(b)

      case ByteBufferBody(b, _) =>
        http4s.EntityEncoder.chunkEncoder[F].contramap(Chunk.byteBuffer).toEntity(b)

      case InputStreamBody(b, _) =>
        http4s.EntityEncoder.inputStreamEncoder[F, InputStream](blocker).toEntity(b.pure[F])

      case FileBody(b, _) =>
        http4s.EntityEncoder.fileEncoder(blocker).toEntity(b.toFile)
    }

  private def bodyToHttp4s(
      body: GenericRequestBody[R],
      contentLength: Option[Long]
  ): (http4s.Entity[F], http4s.Headers) =
    body match {
      case NoBody => (http4s.Entity(http4s.EmptyBody: http4s.EntityBody[F]), http4s.Headers.empty)

      case b: BasicBodyPart => (basicBodyToHttp4s(b), http4s.Headers.empty)

      case StreamBody(s) =>
        (http4s.Entity(s.asInstanceOf[Stream[F, Byte]], contentLength), http4s.Headers.empty)

      case m: MultipartBody[_] =>
        val parts = m.parts.toVector.map(multipartToHttp4s)
        val multipart = http4s.multipart.Multipart(parts)
        (http4s.EntityEncoder.multipartEncoder.toEntity(multipart), multipart.headers)
    }

  private def multipartToHttp4s(mp: Part[BodyPart[_]]): http4s.multipart.Part[F] = {
    val contentDisposition =
      http4s.Header.Raw(CIString(HeaderNames.ContentDisposition), mp.contentDispositionHeaderValue)
    val otherHeaders = mp.headers.map(h => http4s.Header.Raw(CIString(h.name), h.value))
    val allHeaders = List(contentDisposition) ++ otherHeaders

    val body: EntityBody[F] = mp.body match {
      case body: BasicBodyPart => basicBodyToHttp4s(body).body
      case StreamBody(b)       => b.asInstanceOf[EntityBody[F]]
    }

    http4s.multipart.Part(http4s.Headers(allHeaders), body)
  }

  private def onFinalizeSignal(hr: http4s.Response[F], signal: F[Unit]): http4s.Response[F] =
    hr.copy(body = hr.body.onFinalize(signal))

  private def addOnBodyReceivedCallback[T](hr: http4s.Response[F], callback: () => Unit): http4s.Response[F] =
    hr.copy(body =
      hr.body.onFinalizeCase(exitCase =>
        if (exitCase == ExitCase.Completed) ConcurrentEffect[F].delay(callback()) else ConcurrentEffect[F].unit
      )
    )

  private def decompressResponseBodyIfNotHead[T](
      m: Method,
      hr: http4s.Response[F],
      autoDecompressionEnabled: Boolean
  ): http4s.Response[F] =
    if (m == Method.HEAD || !autoDecompressionEnabled) hr else decompressResponseBody(hr)

  private def decompressResponseBody(hr: http4s.Response[F]): http4s.Response[F] = {
    val isEmptyBody: Boolean =
      hr.headers.get[http4s.headers.`Content-Length`].contains(http4s.headers.`Content-Length`.zero)
    val body = hr.headers
      .get[`Content-Encoding`]
      .filterNot(_ => hr.status.equals(Status.NoContent) || isEmptyBody)
      .map(e => Decompressor.decompressIfPossible(hr.body, e.contentCoding.coding, compressionHandlers.decompressors))
      .getOrElse(hr.body)
    hr.copy(body = body)
  }

  private def bodyFromResponseAs(signalBodyComplete: F[Unit]) =
    new BodyFromResponseAs[F, http4s.Response[F], Nothing, EntityBody[F]] {
      override protected def withReplayableBody(
          response: http4s.Response[F],
          replayableBody: Either[Array[Byte], SttpFile]
      ): F[http4s.Response[F]] = {
        val body = replayableBody match {
          case Left(byteArray) => Stream.chunk(Chunk.bytes(byteArray))
          case Right(file)     => fs2.io.file.readAll(file.toPath, blocker, IOBufferSize)
        }

        response.copy(body = body).pure[F]
      }

      override protected def regularIgnore(response: http4s.Response[F]): F[Unit] = response.body.compile.drain

      override protected def regularAsByteArray(response: http4s.Response[F]): F[Array[Byte]] = response.as[Array[Byte]]

      override protected def regularAsFile(response: http4s.Response[F], file: SttpFile): F[SttpFile] = {
        val f = file.toFile
        if (!f.exists()) {
          f.getParentFile.mkdirs()
          f.createNewFile()
        }

        response.body.through(fs2.io.file.writeAll(file.toPath, blocker)).compile.drain.map(_ => file)
      }

      override protected def regularAsStream(response: http4s.Response[F]): F[(EntityBody[F], () => F[Unit])] =
        (response.body, () => signalBodyComplete).pure[F]

      override protected def handleWS[T](
          responseAs: GenericWebSocketResponseAs[T, _],
          meta: ResponseMetadata,
          ws: Nothing
      ): F[T] = ws

      override protected def cleanupWhenNotAWebSocket(
          response: http4s.Response[F],
          e: NotAWebSocketException
      ): F[Unit] = ().pure[F]

      override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): F[Unit] = response
    }

  private def adjustExceptions[T](r: GenericRequest[_, _])(t: => F[T]): F[T] =
    SttpClientException.adjustExceptions(monad)(t)(http4sExceptionToSttpClientException(r, _))

  private def http4sExceptionToSttpClientException(request: GenericRequest[_, _], e: Exception): Option[Exception] =
    e match {
      case e: org.http4s.client.ConnectionFailure => Some(new SttpClientException.ConnectException(request, e))
      case e: org.http4s.InvalidBodyException     => Some(new SttpClientException.ReadException(request, e))
      case e: org.http4s.InvalidResponseException => Some(new SttpClientException.ReadException(request, e))
      case e: Exception => SttpClientException.defaultExceptionToSttpClientException(request, e)
    }

  // based on Fs2Streams.limitBytes (for ce3)
  private def limitBytes[F[_]](stream: Stream[F, Byte], maxBytes: Long): Stream[F, Byte] = {
    def go(s: Stream[F, Byte], remaining: Long): Pull[F, Byte, Unit] = {
      if (remaining < 0) throw new StreamMaxLengthExceededException(maxBytes)
      else
        s.pull.uncons.flatMap {
          case Some((chunk, tail)) =>
            val chunkSize = chunk.size.toLong
            if (chunkSize <= remaining)
              Pull.output(chunk) >> go(tail, remaining - chunkSize)
            else
              throw new StreamMaxLengthExceededException(maxBytes)
          case None => Pull.done
        }
    }
    go(stream, maxBytes).stream
  }

  override implicit val monad: MonadError[F] = new CatsMonadAsyncError

  // no-op. Client lifecycle is managed by Resource
  override def close(): F[Unit] = monad.unit(())
}

object Http4sBackend {
  def defaultCompressionHandlers[F[_]: Sync]: CompressionHandlers[Fs2Streams[F], Stream[F, Byte]] =
    CompressionHandlers(
      Compressor.default[Fs2Streams[F]],
      List(new GZipFs2Decompressor, new DeflateFs2Decompressor)
    )

  def usingClient[F[_]: ConcurrentEffect: ContextShift](
      client: Client[F],
      blocker: Blocker,
      customizeRequest: Http4sRequest[F] => Http4sRequest[F] = identity[Http4sRequest[F]] _,
      compressionHandlers: Sync[F] => CompressionHandlers[Fs2Streams[F], EntityBody[F]] =
        defaultCompressionHandlers[F](_: Sync[F])
  ): StreamBackend[F, Fs2Streams[F]] =
    FollowRedirectsBackend(new Http4sBackend[F](client, blocker, customizeRequest, compressionHandlers(implicitly)))

  def usingBlazeClientBuilder[F[_]: ConcurrentEffect: ContextShift](
      blazeClientBuilder: BlazeClientBuilder[F],
      blocker: Blocker,
      customizeRequest: Http4sRequest[F] => Http4sRequest[F] = identity[Http4sRequest[F]] _,
      compressionHandlers: Sync[F] => CompressionHandlers[Fs2Streams[F], EntityBody[F]] =
        defaultCompressionHandlers[F](_: Sync[F])
  ): Resource[F, StreamBackend[F, Fs2Streams[F]]] =
    blazeClientBuilder.resource.map(c => usingClient(c, blocker, customizeRequest, compressionHandlers))

  def usingDefaultBlazeClientBuilder[F[_]: ConcurrentEffect: ContextShift](
      blocker: Blocker,
      clientExecutionContext: ExecutionContext = ExecutionContext.global,
      customizeRequest: Http4sRequest[F] => Http4sRequest[F] = identity[Http4sRequest[F]] _,
      compressionHandlers: Sync[F] => CompressionHandlers[Fs2Streams[F], EntityBody[F]] =
        defaultCompressionHandlers[F](_: Sync[F])
  ): Resource[F, StreamBackend[F, Fs2Streams[F]]] =
    usingBlazeClientBuilder(
      BlazeClientBuilder[F](clientExecutionContext),
      blocker,
      customizeRequest,
      compressionHandlers
    )

  /** Create a stub backend for testing, which uses the `F` response wrapper, and supports `Stream[F, Byte]` streaming.
    *
    * See [[StreamBackendStub]] for details on how to configure stub responses.
    */
  def stub[F[_]: Concurrent]: StreamBackendStub[F, Fs2Streams[F]] = StreamBackendStub(new CatsMonadAsyncError)
}
