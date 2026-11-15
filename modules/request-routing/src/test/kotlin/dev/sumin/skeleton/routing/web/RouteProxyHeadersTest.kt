package dev.sumin.skeleton.routing.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.routing.Route
import dev.sumin.skeleton.common.routing.RouteEntry
import dev.sumin.skeleton.routing.RouteTarget
import dev.sumin.skeleton.routing.RoutingErrorCode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** 대상 응답 머리를 옮기는 규칙과 나가는 머리 — 대상 응답을 직접 지어 주는 가짜 클라이언트로 본다(HTTP/2 의 `:status` 는 평문 서버로 못 만든다) */
class RouteProxyHeadersTest {
    private val route = Route(listOf(RouteEntry("api", "candidate")))
    private val target = RouteTarget("api", "candidate", URI("http://candidate.invalid"), false, emptyMap())

    private class FakeClient(private val upstreamHeaders: Map<String, List<String>>) : HttpClient() {
        var sent: HttpRequest? = null

        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            sent = request
            @Suppress("UNCHECKED_CAST")
            return object : HttpResponse<InputStream> {
                override fun statusCode() = 200
                override fun request() = request
                override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
                override fun headers() = HttpHeaders.of(upstreamHeaders) { _, _ -> true }
                override fun body(): InputStream = ByteArrayInputStream(ByteArray(0))
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri() = request.uri()
                override fun version() = Version.HTTP_1_1
            } as HttpResponse<T>
        }

        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>?): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters(): SSLParameters = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
    }

    private fun forward(client: FakeClient, request: MockHttpServletRequest, response: MockHttpServletResponse) =
        RouteProxy(client, Duration.ofSeconds(1)).forward(request, response, target, route)

    @Test
    fun http2PseudoHeaderIsNotCopiedToTheResponse() {
        val response = MockHttpServletResponse()

        forward(FakeClient(mapOf(":status" to listOf("200"), "X-Candidate" to listOf("yes"))), MockHttpServletRequest("GET", "/a"), response)

        assertNull(response.getHeader(":status"))
        assertEquals("yes", response.getHeader("X-Candidate"))
    }

    @Test
    fun capitalisedBaggageHeaderIsReplacedNotDuplicated() {
        val client = FakeClient(emptyMap())
        val request = MockHttpServletRequest("GET", "/a").apply { addHeader("Baggage", "userId=7,route=api:candidate") }

        forward(client, request, MockHttpServletResponse())

        assertEquals(listOf("userId=7,route=api:candidate"), client.sent!!.headers().map().filterKeys { it.equals("baggage", true) }.values.flatten())
    }

    @Test
    fun queryTheUriCannotHoldIsMalformedNot500() {
        val request = MockHttpServletRequest("GET", "/a").apply { queryString = "q=|" }

        val error = assertFailsWith<ApplicationException> { forward(FakeClient(emptyMap()), request, MockHttpServletResponse()) }

        assertEquals(RoutingErrorCode.MALFORMED, error.errorCode)
    }

    @Test
    fun setCookieIsAppendedNeverReplaced() {
        val response = MockHttpServletResponse().apply { addHeader("Set-Cookie", "entrance=1") }

        forward(FakeClient(mapOf("set-cookie" to listOf("a=1", "b=2"))), MockHttpServletRequest("GET", "/a"), response)

        assertEquals(listOf("entrance=1", "a=1", "b=2"), response.getHeaders("Set-Cookie"))
    }

    @Test
    fun targetHeaderReplacesOneTheEntranceAlreadySet() {
        val response = MockHttpServletResponse().apply { setHeader("Access-Control-Allow-Origin", "https://entrance.example") }

        forward(FakeClient(mapOf("Access-Control-Allow-Origin" to listOf("https://candidate.example"))), MockHttpServletRequest("GET", "/a"), response)

        assertEquals(listOf("https://candidate.example"), response.getHeaders("Access-Control-Allow-Origin"))
    }
}
