package dev.sumin.skeleton.common.http

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.MDC
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.web.reactive.function.client.WebClient

/** 시험 서버는 127.0.0.1 에 묶는다 — 와일드카드(`InetSocketAddress(0)`)는 다른 프로세스의 127.0.0.1 리스너에 연결이 가로채일 수 있다 (TestServerRulesTest). */
/** 부하가 큰 호스트(load 60~90)에서도 같은 결과여야 한다: 상한은 넉넉하게, 시간 초과 시험은 서버가 응답을 잡아 두는 방식으로. */
private val GENEROUS: Duration = Duration.ofSeconds(30)

@ExtendWith(OutputCaptureExtension::class)
class ExternalHttpClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: ExternalHttpClient
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val releaseSlow = CountDownLatch(1)

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        client = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                defaultConnectTimeout = GENEROUS,
                defaultResponseTimeout = GENEROUS,
                clients = mapOf(
                    "test" to OutboundHttpProperties.Client(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            ),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )
    }

    @AfterTest
    fun tearDown() {
        MDC.clear()
        releaseSlow.countDown()
        server.stop(0)
    }

    @Test
    fun `standard methods send expected method path and body`() {
        assertEquals("GET", client.get("test", "/methods/get", EchoResponse::class.java).block()?.method)
        assertEquals("POST", client.post("test", "/methods/post", mapOf("name" to "post"), EchoResponse::class.java).block()?.method)
        assertEquals("PUT", client.put("test", "/methods/put", mapOf("name" to "put"), EchoResponse::class.java).block()?.method)
        assertEquals("PATCH", client.patch("test", "/methods/patch", mapOf("name" to "patch"), EchoResponse::class.java).block()?.method)
        assertEquals("DELETE", client.delete("test", "/methods/delete", EchoResponse::class.java).block()?.method)

        assertEquals(listOf("GET", "POST", "PUT", "PATCH", "DELETE"), requests.map { it.method })
        assertEquals("""{"name":"post"}""", requests.first { it.method == "POST" }.body)
        assertEquals("""{"name":"put"}""", requests.first { it.method == "PUT" }.body)
        assertEquals("""{"name":"patch"}""", requests.first { it.method == "PATCH" }.body)
    }

    @Test
    fun `per call manipulation applies uri variables query params headers and timeout`() {
        val response = client.get("test", "/items/{id}", EchoResponse::class.java) {
            uriVariable("id", "item-1")
            queryParam("expand", "customer")
            header("X-Test-Header", "custom")
            timeout(GENEROUS)
        }.block()

        assertEquals("GET", response?.method)
        val request = requests.single()
        assertEquals("/items/item-1", request.path)
        assertEquals("expand=customer", request.query)
        assertEquals("custom", request.headers["x-test-header"]?.single())
    }

    @Test
    fun `per call manipulation can override base url`() {
        val response = client.get("dynamic", "/base-override", EchoResponse::class.java) {
            baseUrl("http://127.0.0.1:${server.address.port}")
        }.block()

        assertEquals("GET", response?.method)
        assertEquals("/base-override", requests.single().path)
    }

    @Test
    fun `endpoint abstraction keeps vendor clients from repeating client name and path`() {
        val endpoint = ExternalHttpEndpoint(clientName = "test", path = "/methods/get")

        val response = client.get(endpoint, EchoResponse::class.java) {
            header("X-Endpoint-Test", "true")
        }.block()

        assertEquals("GET", response?.method)
        assertEquals("/methods/get", requests.single().path)
        assertEquals("true", requests.single().headers["x-endpoint-test"]?.single())
    }

    @Test
    fun `post form sends application form urlencoded body`() {
        client.postForm(
            clientName = "test",
            path = "/oauth/token",
            form = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to "abc 123",
                "client_id" to "client",
            ),
            responseType = EchoResponse::class.java,
        ).block()

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/oauth/token", request.path)
        assertTrue(request.headers["content-type"]?.single()?.startsWith("application/x-www-form-urlencoded") == true)
        assertEquals("grant_type=authorization_code&code=abc+123&client_id=client", request.body)
    }

    @Test
    fun `response variants expose upstream status headers and body`() {
        val response = client.postResponse(
            clientName = "test",
            path = "/with-headers",
            body = mapOf("name" to "header-test"),
            responseType = EchoResponse::class.java,
        ).block()

        assertNotNull(response)
        assertEquals(201, response.statusCode)
        assertEquals("provider-trace-123", response.headers.getFirst("X-Provider-Trace-Id"))
        assertEquals("provider-trace-123", response.trace.traceId)
        assertEquals("X-Provider-Trace-Id", response.trace.source)
        assertEquals("POST", response.body.method)
    }

    @Test
    fun `per call vendor trace headers override default extraction`() {
        val response = client.getResponse("test", "/with-alt-trace", EchoResponse::class.java) {
            vendorTraceHeader("X-Alt-Trace")
        }.block()

        assertNotNull(response)
        assertEquals("alt-trace-456", response.trace.traceId)
        assertEquals("X-Alt-Trace", response.trace.source)
    }

    @Test
    fun `trace headers propagate from MDC`() {
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")

        client.get("test", "/trace", EchoResponse::class.java).block()

        val request = requests.single()
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", request.headers["x-trace-id"]?.single())
        assertEquals(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            request.headers["traceparent"]?.single(),
        )
    }

    @Test
    fun `external http logs redact sensitive query parameters`(output: CapturedOutput) {
        val queryLoggingClient = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                defaultConnectTimeout = GENEROUS,
                defaultResponseTimeout = GENEROUS,
                logging = OutboundHttpProperties.Logging(includeQuery = true),
                clients = mapOf(
                    "test" to OutboundHttpProperties.Client(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            ),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )

        queryLoggingClient.get(
            clientName = "test",
            path = "/methods/get?access_token=secret-token&orderId=order-1",
            responseType = EchoResponse::class.java,
        ).block()

        assertTrue(output.all.contains("access_token=[REDACTED]"))
        assertTrue(output.all.contains("orderId=order-1"))
        assertFalse(output.all.contains("secret-token"))
    }

    @Test
    fun `external http completion logs use stable external http debug category`() {
        captureLogger(SkeletonLoggers.EXTERNAL_HTTP).use { captured ->
            client.get("test", "/methods/get", EchoResponse::class.java).block()

            val messages = captured.events.map { it.formattedMessage }
            assertTrue(messages.any { it.startsWith("External HTTP GET test /methods/get") })
        }
    }

    @Test
    fun `upstream error maps to status exception`() {
        val exception = assertFailsWith<ExternalHttpStatusException> {
            client.get("test", "/error-with-trace", EchoResponse::class.java).block()
        }

        assertEquals("test", exception.clientName)
        assertEquals(503, exception.upstreamStatus)
        assertEquals("External service error", exception.title)
        assertEquals("error-trace-789", exception.providerTraceId)
    }

    @Test
    fun `timeout maps to timeout exception`() {
        // 서버가 응답을 잡아 두므로(releaseSlow 전까지) 시간 초과는 부하와 무관하게 반드시 난다 — 벽시계 시간을 단언하지 않고 예외 종류만 본다
        val timeoutClient = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                defaultConnectTimeout = GENEROUS,
                defaultResponseTimeout = Duration.ofMillis(250),
                clients = mapOf(
                    "test" to OutboundHttpProperties.Client(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
            ),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )

        val exception = assertFailsWith<ExternalHttpTimeoutException> {
            timeoutClient.get("test", "/slow", EchoResponse::class.java).block(GENEROUS)
        }

        assertEquals("test", exception.clientName)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests += RecordedRequest(
            method = exchange.requestMethod,
            path = exchange.requestURI.path,
            query = exchange.requestURI.query,
            headers = exchange.requestHeaders.mapKeys { it.key.lowercase() },
            body = body,
        )

        when (exchange.requestURI.path) {
            "/error" -> exchange.respond(503, """{"message":"upstream unavailable"}""")
            "/error-with-trace" -> {
                exchange.responseHeaders.add("X-Provider-Trace-Id", "error-trace-789")
                exchange.respond(503, """{"message":"upstream unavailable"}""")
            }
            "/slow" -> {
                // 시험이 끝날 때(tearDown)까지 응답을 잡아 둔다 — 고정 sleep 은 부하에서 클라이언트 시간 초과와 경주가 된다
                releaseSlow.await(GENEROUS.toSeconds(), TimeUnit.SECONDS)
                runCatching { exchange.respond(200, """{"method":"${exchange.requestMethod}"}""") }
            }
            "/with-headers" -> {
                exchange.responseHeaders.add("X-Provider-Trace-Id", "provider-trace-123")
                exchange.respond(201, """{"method":"${exchange.requestMethod}"}""")
            }
            "/with-alt-trace" -> {
                exchange.responseHeaders.add("X-Alt-Trace", "alt-trace-456")
                exchange.respond(200, """{"method":"${exchange.requestMethod}"}""")
            }
            else -> exchange.respond(200, """{"method":"${exchange.requestMethod}"}""")
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, body.toByteArray().size.toLong())
        responseBody.use { output -> output.write(body.toByteArray()) }
    }

    data class EchoResponse(
        val method: String,
    )

    data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    )

    private fun captureLogger(loggerName: String): CapturedLogger {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        logger.level = Level.INFO
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return CapturedLogger(logger, appender, previousLevel, previousAdditive)
    }

    private class CapturedLogger(
        private val logger: Logger,
        private val appender: ListAppender<ILoggingEvent>,
        private val previousLevel: Level?,
        private val previousAdditive: Boolean,
    ) : AutoCloseable {
        val events: List<ILoggingEvent>
            get() = appender.list

        override fun close() {
            logger.detachAppender(appender)
            logger.level = previousLevel
            logger.isAdditive = previousAdditive
            appender.stop()
        }
    }
}
