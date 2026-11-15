package dev.sumin.skeleton.routing.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.routing.Route
import dev.sumin.skeleton.common.routing.RouteBaggage
import dev.sumin.skeleton.routing.RouteTarget
import dev.sumin.skeleton.routing.RoutingErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections

/**
 * 입구 서비스 자신이 대상일 때 요청을 통째로 대상에 넘기고 응답을 읽는 대로 흘려 돌려준다(SSE 도 한 덩이씩 흐른다).
 * 못 넘기면 [ApplicationException] — 필터가 응답으로 옮긴다([RoutingErrorCode.LOOP] · [RoutingErrorCode.TARGET_UNREACHABLE]).
 */
class RouteProxy(private val client: HttpClient, private val timeout: Duration) {
    fun forward(request: HttpServletRequest, response: HttpServletResponse, target: RouteTarget, route: Route) {
        val via = Collections.list(request.getHeaders(VIA))
        if (via.any { VIA_TOKEN in it }) {
            throw ApplicationException("The request was already forwarded once.", RoutingErrorCode.LOOP)
        }
        val upstream = try {
            client.send(outgoing(request, target, route, via), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: IOException) {
            throw unreachable(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unreachable(e)
        }
        upstream.body().use { body ->
            response.status = upstream.statusCode()
            val dropped = hopByHop(upstream.headers().allValues("connection"))
            upstream.headers().map().forEach { (name, values) ->
                if (!skip(name, dropped)) values.forEach { response.addHeader(name, it) }
            }
            response.flushBuffer()
            copy(body, response)
        }
    }

    private fun outgoing(request: HttpServletRequest, target: RouteTarget, route: Route, via: List<String>): HttpRequest {
        val dropped = hopByHop(Collections.list(request.getHeaders("Connection")))
        val builder = HttpRequest.newBuilder(uri(request, target))
            .timeout(timeout)
            .method(request.method, body(request))
        Collections.list(request.headerNames).forEach { name ->
            if (!skip(name, dropped) && name != RouteBaggage.HEADER && !name.equals(VIA, true)) {
                Collections.list(request.getHeaders(name)).forEach { builder.header(name, it) }
            }
        }
        val baggage = Collections.list(request.getHeaders(RouteBaggage.HEADER)).joinToString(",").ifEmpty { null }
        builder.header(RouteBaggage.HEADER, RouteBaggage.withRoute(baggage, route))
        builder.header(VIA, (via + "1.1 $VIA_TOKEN").joinToString(", "))
        target.headers.forEach { (name, value) -> builder.setHeader(name, value) }
        return builder.build()
    }

    private fun uri(request: HttpServletRequest, target: RouteTarget): URI =
        URI.create(target.origin.toString().trimEnd('/') + request.requestURI + (request.queryString?.let { "?$it" } ?: ""))

    private fun body(request: HttpServletRequest): HttpRequest.BodyPublisher {
        val length = request.contentLengthLong
        val streamed = HttpRequest.BodyPublishers.ofInputStream { request.inputStream }
        return when {
            length > 0 -> HttpRequest.BodyPublishers.fromPublisher(streamed, length)
            length < 0 && request.getHeader("Transfer-Encoding") != null -> streamed
            else -> HttpRequest.BodyPublishers.noBody()
        }
    }

    private fun copy(body: InputStream, response: HttpServletResponse) {
        val buffer = ByteArray(BUFFER_SIZE)
        val out = response.outputStream
        while (true) {
            val read = body.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            out.flush()
        }
    }

    /** `Connection` 머리에 적힌 이름들 — 이것도 그 한 구간만의 머리다 */
    private fun hopByHop(connection: List<String>): Set<String> =
        connection.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** 넘기지 않는 머리 — `Proxy-*` · 고정 목록 · `Connection` 에 적힌 것. `Content-Length` 는 몸통 발행자가 다시 정한다 */
    private fun skip(name: String, listed: Set<String>): Boolean {
        val lower = name.lowercase()
        return lower in ALWAYS_DROPPED || lower.startsWith("proxy-") || lower in listed
    }

    private fun unreachable(cause: Exception) =
        ApplicationException("The route target did not answer.", RoutingErrorCode.TARGET_UNREACHABLE, cause = cause)

    private companion object {
        const val VIA = "Via"
        const val VIA_TOKEN = "request-routing"
        const val BUFFER_SIZE = 8192

        // Expect · Host · Upgrade 는 JDK HttpClient 도 직접 못 싣게 막아 둔 머리다
        val ALWAYS_DROPPED = setOf(
            "connection", "keep-alive", "te", "trailer",
            "transfer-encoding", "upgrade", "host", "content-length", "expect",
        )
    }
}
