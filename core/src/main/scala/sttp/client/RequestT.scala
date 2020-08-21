package sttp.client

import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Base64

import sttp.capabilities.{Effect, Streams}
import sttp.client.internal.DigestAuthenticator.DigestAuthData
import sttp.client.internal._
import sttp.client.internal.{SttpFile, ToCurlConverter}
import sttp.model._

import scala.collection.immutable.Seq
import scala.concurrent.duration.Duration

/**
  * Describes a HTTP request, along with a description of how the response body should be handled.
  *
  * The request can be sent using a [[SttpBackend]], which provides a superset of the required capabilities.
  *
  * @param response Description of how the response body should be handled.
  *                 Needs to be specified upfront so that the response
  *                 is always consumed and hence there are no requirements on
  *                 client code to consume it. An exception to this are
  *                 unsafe streaming and websocket responses, which need to
  *                 be consumed/closed by the client.
  * @param tags Request-specific tags which can be used by backends for
  *             logging, metrics, etc. Not used by default.
  * @tparam U Specifies if the method & uri are specified. By default can be
  *           either:
  *           * [[Empty]], which is a type constructor which always resolves to
  *           [[None]]. This type of request is aliased to [[PartialRequest]]:
  *           there's no method and uri specified, and the request cannot be
  *           sent.
  *           * [[Identity]], which is an identity type constructor. This type of
  *           request is aliased to [[Request]]: the method and uri are
  *           specified, and the request can be sent.
  * @tparam T The target type, to which the response body should be read.
  * @tparam R The backend capabilities required by the request or response description. This might be `Any` (no
  *           requirements), [[Effect]] (the backend must support the given effect type), [[Streams]] (the ability to
  *           send and receive streaming bodies) or [[WebSockets]] (the ability to handle websocket requests).
  */
case class RequestT[U[_], T, -R](
    method: U[Method],
    uri: U[Uri],
    body: RequestBody[R],
    headers: Seq[Header],
    response: ResponseAs[T, R],
    options: RequestOptions,
    tags: Map[String, Any]
) extends RequestTExtensions[U, T, R] {
  def get(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.GET)
  def head(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.HEAD)
  def post(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.POST)
  def put(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.PUT)
  def delete(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.DELETE)
  def options(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.OPTIONS)
  def patch(uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = Method.PATCH)
  def method(method: Method, uri: Uri): Request[T, R] =
    this.copy[Identity, T, R](uri = uri, method = method)

  def contentType(ct: String): RequestT[U, T, R] =
    header(HeaderNames.ContentType, ct, replaceExisting = true)
  def contentType(mt: MediaType): RequestT[U, T, R] =
    header(HeaderNames.ContentType, mt.toString, replaceExisting = true)
  def contentType(ct: String, encoding: String): RequestT[U, T, R] =
    header(HeaderNames.ContentType, contentTypeWithCharset(ct, encoding), replaceExisting = true)
  def contentLength(l: Long): RequestT[U, T, R] =
    header(HeaderNames.ContentLength, l.toString, replaceExisting = true)

  /**
    * Adds the given header to the end of the headers sequence.
    * @param replaceExisting If there's already a header with the same name, should it be dropped?
    */
  def header(h: Header, replaceExisting: Boolean = false): RequestT[U, T, R] = {
    val current = if (replaceExisting) headers.filterNot(_.is(h.name)) else headers
    this.copy(headers = current :+ h)
  }

  /**
    * Adds the given header to the end of the headers sequence.
    * @param replaceExisting If there's already a header with the same name, should it be dropped?
    */
  def header(k: String, v: String, replaceExisting: Boolean): RequestT[U, T, R] =
    header(Header(k, v), replaceExisting)
  def header(k: String, v: String): RequestT[U, T, R] = header(Header(k, v))
  def header(k: String, ov: Option[String]): RequestT[U, T, R] = ov.fold(this)(header(k, _))
  def headers(hs: Map[String, String]): RequestT[U, T, R] =
    headers(hs.map(t => Header(t._1, t._2)).toSeq: _*)
  def headers(hs: Header*): RequestT[U, T, R] = this.copy(headers = headers ++ hs)
  def auth: SpecifyAuthScheme[U, T, R] =
    new SpecifyAuthScheme[U, T, R](HeaderNames.Authorization, this, DigestAuthenticationBackend.DigestAuthTag)
  def proxyAuth: SpecifyAuthScheme[U, T, R] =
    new SpecifyAuthScheme[U, T, R](HeaderNames.ProxyAuthorization, this, DigestAuthenticationBackend.ProxyDigestAuthTag)
  def acceptEncoding(encoding: String): RequestT[U, T, R] =
    header(HeaderNames.AcceptEncoding, encoding, replaceExisting = true)

  def cookie(nv: (String, String)): RequestT[U, T, R] = cookies(nv)
  def cookie(n: String, v: String): RequestT[U, T, R] = cookies((n, v))
  def cookies(r: Response[_]): RequestT[U, T, R] = cookies(r.cookies.map(c => (c.name, c.value)): _*)
  def cookies(cs: Iterable[CookieWithMeta]): RequestT[U, T, R] = cookies(cs.map(c => (c.name, c.value)).toSeq: _*)
  def cookies(nvs: (String, String)*): RequestT[U, T, R] = {
    header(
      HeaderNames.Cookie,
      (headers.find(_.name == HeaderNames.Cookie).map(_.value).toSeq ++ nvs.map(p => p._1 + "=" + p._2)).mkString("; "),
      replaceExisting = true
    )
  }

  /**
    * Uses the `utf-8` encoding.
    *
    * If content type is not yet specified, will be set to `text/plain`
    * with `utf-8` encoding.
    *
    * If content length is not yet specified, will be set to the number of
    * bytes in the string using the `utf-8` encoding.
    */
  def body(b: String): RequestT[U, T, R] = body(b, Utf8)

  /**
    * If content type is not yet specified, will be set to `text/plain`
    * with the given encoding.
    *
    * If content length is not yet specified, will be set to the number of
    * bytes in the string using the given encoding.
    */
  def body(b: String, encoding: String): RequestT[U, T, R] =
    withBasicBody(StringBody(b, encoding))
      .setContentLengthIfMissing(b.getBytes(encoding).length.toLong)

  /**
    * If content type is not yet specified, will be set to
    * `application/octet-stream`.
    *
    * If content length is not yet specified, will be set to the length
    * of the given array.
    */
  def body(b: Array[Byte]): RequestT[U, T, R] =
    withBasicBody(ByteArrayBody(b))
      .setContentLengthIfMissing(b.length.toLong)

  /**
    * If content type is not yet specified, will be set to
    * `application/octet-stream`.
    */
  def body(b: ByteBuffer): RequestT[U, T, R] =
    withBasicBody(ByteBufferBody(b))

  /**
    * If content type is not yet specified, will be set to
    * `application/octet-stream`.
    */
  def body(b: InputStream): RequestT[U, T, R] =
    withBasicBody(InputStreamBody(b))

  /**
    * If content type is not yet specified, will be set to
    * `application/octet-stream`.
    *
    * If content length is not yet specified, will be set to the length
    * of the given file.
    */
  private[client] def body(f: SttpFile): RequestT[U, T, R] =
    withBasicBody(FileBody(f))
      .setContentLengthIfMissing(f.size)

  /**
    * Encodes the given parameters as form data using `utf-8`.
    * If content type is not yet specified, will be set to
    * `application/x-www-form-urlencoded`.
    *
    * If content length is not yet specified, will be set to the length
    * of the number of bytes in the url-encoded parameter string.
    */
  def body(fs: Map[String, String]): RequestT[U, T, R] =
    formDataBody(fs.toList, Utf8)

  /**
    * Encodes the given parameters as form data.
    * If content type is not yet specified, will be set to
    * `application/x-www-form-urlencoded`.
    *
    * If content length is not yet specified, will be set to the length
    * of the number of bytes in the url-encoded parameter string.
    */
  def body(fs: Map[String, String], encoding: String): RequestT[U, T, R] =
    formDataBody(fs.toList, encoding)

  /**
    * Encodes the given parameters as form data using `utf-8`.
    * If content type is not yet specified, will be set to
    * `application/x-www-form-urlencoded`.
    *
    * If content length is not yet specified, will be set to the length
    * of the number of bytes in the url-encoded parameter string.
    */
  def body(fs: (String, String)*): RequestT[U, T, R] =
    formDataBody(fs.toList, Utf8)

  /**
    * Encodes the given parameters as form data.
    * If content type is not yet specified, will be set to
    * `application/x-www-form-urlencoded`.
    *
    * If content length is not yet specified, will be set to the length
    * of the number of bytes in the url-encoded parameter string.
    */
  def body(fs: Seq[(String, String)], encoding: String): RequestT[U, T, R] =
    formDataBody(fs, encoding)

  def multipartBody(ps: Seq[Part[BasicRequestBody]]): RequestT[U, T, R] =
    this.copy(body = MultipartBody(ps))

  def multipartBody(p1: Part[BasicRequestBody], ps: Part[BasicRequestBody]*): RequestT[U, T, R] =
    this.copy(body = MultipartBody(p1 :: ps.toList))

  def streamBody[S](s: Streams[S])(b: s.BinaryStream): RequestT[U, T, R with S] =
    copy[U, T, R with S](body = StreamBody(s)(b))

  def readTimeout(t: Duration): RequestT[U, T, R] =
    this.copy(options = options.copy(readTimeout = t))

  /**
    * Specifies the target type to which the response body should be read.
    * Note that this replaces any previous specifications, which also includes
    * any previous `mapResponse` invocations.
    */
  def response[T2, R2](ra: ResponseAs[T2, R2]): RequestT[U, T2, R with R2] =
    this.copy(response = ra)

  def mapResponse[T2](f: T => T2): RequestT[U, T2, R] =
    this.copy(response = response.map(f))

  def isWebSocket: Boolean = ResponseAs.isWebSocket(response)

  def followRedirects(fr: Boolean): RequestT[U, T, R] =
    this.copy(options = options.copy(followRedirects = fr))

  def maxRedirects(n: Int): RequestT[U, T, R] =
    if (n <= 0)
      this.copy(options = options.copy(followRedirects = false))
    else
      this.copy(options = options.copy(followRedirects = true, maxRedirects = n))

  def tag(k: String, v: Any): RequestT[U, T, R] =
    this.copy(tags = tags + (k -> v))

  def tag(k: String): Option[Any] = tags.get(k)

  /**
    * When a POST or PUT request is redirected, should the redirect be a POST/PUT as well (with the original body),
    * or should the request be converted to a GET without a body.
    *
    * Note that this only affects 301 and 302 redirects.
    * 303 redirects are always converted, while 307 and 308 redirects always keep the same method.
    *
    * See https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections for details.
    */
  def redirectToGet(r: Boolean): RequestT[U, T, R] =
    this.copy(options = options.copy(redirectToGet = r))

  /**
    * Sends the request, using the backend from the implicit scope. Only requests for which the method & URI are
    * specified can be sent.
    *
    * The required capabilities must be a subset of the capabilities provided by the backend.
    *
    * @return For synchronous backends (when the effect type is [[Identity]]), [[Response]] is returned directly
    *         and exceptions are thrown.
    *         For asynchronous backends (when the effect type is e.g. [[scala.concurrent.Future]]), an effect containing
    *         the [[Response]] is returned. Exceptions are represented as failed effects (e.g. failed futures).
    *
    *         The response body is deserialized as specified by this request (see [[RequestT.response]]).
    *
    *         Known exceptions are converted by backends to one of [[SttpClientException]]. Other exceptions are thrown
    *         unchanged.
    */
  @deprecated(message = "use request.send(backend), providing the backend explicitly", since = "3.0.0")
  def send[F[_], P]()(implicit
      backend: SttpBackend[F, P],
      pEffectFIsR: P with Effect[F] <:< R,
      isIdInRequest: IsIdInRequest[U]
  ): F[Response[T]] =
    send(backend)(
      isIdInRequest,
      pEffectFIsR
    ) // the order of implicits must be different so that the signatures are different

  /**
    * Sends the request, using the given backend. Only requests for which the method & URI are specified can be sent.
    *
    * The required capabilities must be a subset of the capabilities provided by the backend.
    *
    * @return For synchronous backends (when the effect type is [[Identity]]), [[Response]] is returned directly
    *         and exceptions are thrown.
    *         For asynchronous backends (when the effect type is e.g. [[scala.concurrent.Future]]), an effect containing
    *         the [[Response]] is returned. Exceptions are represented as failed effects (e.g. failed futures).
    *
    *         The response body is deserialized as specified by this request (see [[RequestT.response]]).
    *
    *         Known exceptions are converted by backends to one of [[SttpClientException]]. Other exceptions are thrown
    *         unchanged.
    */
  def send[F[_], P](backend: SttpBackend[F, P])(implicit
      isIdInRequest: IsIdInRequest[U],
      pEffectFIsR: P with Effect[F] <:< R
  ): F[Response[T]] = backend.send(asRequest.asInstanceOf[Request[T, P with Effect[F]]]) // as witnessed by pEffectFIsR

  def toCurl(implicit isIdInRequest: IsIdInRequest[U]): String = ToCurlConverter.requestToCurl(asRequest)

  private def asRequest(implicit isIdInRequest: IsIdInRequest[U]): RequestT[Identity, T, R] = {
    // we could avoid the asInstanceOf by creating an artificial copy
    // changing the method & url fields using `isIdInRequest`, but that
    // would be only to satisfy the type checker, and a needless copy at
    // runtime.
    this.asInstanceOf[RequestT[Identity, T, R]]
  }

  private def hasContentType: Boolean = headers.exists(_.is(HeaderNames.ContentType))
  private def setContentTypeIfMissing(mt: MediaType): RequestT[U, T, R] =
    if (hasContentType) this else contentType(mt)

  private[client] def withBasicBody(body: BasicRequestBody) = {
    val defaultCt = body match {
      case StringBody(_, encoding, ct) =>
        ct.copy(charset = Some(encoding))
      case _ =>
        body.defaultContentType
    }

    setContentTypeIfMissing(defaultCt).copy(body = body)
  }

  private def hasContentLength: Boolean =
    headers.exists(_.name.equalsIgnoreCase(HeaderNames.ContentLength))
  private def setContentLengthIfMissing(l: => Long): RequestT[U, T, R] =
    if (hasContentLength) this else contentLength(l)

  private def formDataBody(fs: Seq[(String, String)], encoding: String): RequestT[U, T, R] = {
    val b = RequestBody.paramsToStringBody(fs, encoding)
    setContentTypeIfMissing(MediaType.ApplicationXWwwFormUrlencoded)
      .setContentLengthIfMissing(b.s.getBytes(encoding).length.toLong)
      .copy(body = b)
  }
}

object RequestT {
  implicit class RichRequestTEither[U[_], A, B, R](r: RequestT[U, Either[A, B], R]) {
    def mapResponseRight[B2](f: B => B2): RequestT[U, Either[A, B2], R] = r.copy(response = r.response.mapRight(f))
  }
}

class SpecifyAuthScheme[U[_], T, -R](hn: String, rt: RequestT[U, T, R], digestTag: String) {
  def basic(user: String, password: String): RequestT[U, T, R] = {
    val c = new String(Base64.getEncoder.encode(s"$user:$password".getBytes(Utf8)), Utf8)
    rt.header(hn, s"Basic $c")
  }

  def basicToken(token: String): RequestT[U, T, R] =
    rt.header(hn, s"Basic $token")

  def bearer(token: String): RequestT[U, T, R] =
    rt.header(hn, s"Bearer $token")

  def digest(user: String, password: String): RequestT[U, T, R] = {
    rt.tag(digestTag, DigestAuthData(user, password))
  }
}

case class RequestOptions(
    followRedirects: Boolean,
    readTimeout: Duration,
    maxRedirects: Int,
    redirectToGet: Boolean
)
