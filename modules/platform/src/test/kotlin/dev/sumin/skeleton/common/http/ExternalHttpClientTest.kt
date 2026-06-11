package dev.sumin.skeleton.common.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.TraceIdFilter
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.MDC
import org.springframework.web.reactive.function.client.WebClient

class ExternalHttpClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: ExternalHttpClient
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        client = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                defaultResponseTimeout = Duration.ofSeconds(2),
                clients = mapOf(
                    "test" to OutboundHttpProperties.Client(
                        baseUrl = "http://localhost:${server.address.port}",
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
            timeout(Duration.ofSeconds(1))
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
            baseUrl("http://localhost:${server.address.port}")
        }.block()

        assertEquals("GET", response?.method)
        assertEquals("/base-override", requests.single().path)
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
        assertEquals("POST", response.body.method)
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
    fun `upstream error maps to status exception`() {
        val exception = assertFailsWith<ExternalHttpStatusException> {
            client.get("test", "/error", EchoResponse::class.java).block()
        }

        assertEquals("test", exception.clientName)
        assertEquals(503, exception.upstreamStatus)
        assertEquals("External service error", exception.title)
    }

    @Test
    fun `timeout maps to timeout exception`() {
        val timeoutClient = DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                defaultResponseTimeout = Duration.ofMillis(50),
                clients = mapOf(
                    "test" to OutboundHttpProperties.Client(
                        baseUrl = "http://localhost:${server.address.port}",
                    ),
                ),
            ),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = emptyList(),
        )

        val exception = assertFailsWith<ExternalHttpTimeoutException> {
            timeoutClient.get("test", "/slow", EchoResponse::class.java).block()
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
            "/slow" -> {
                Thread.sleep(300)
                exchange.respond(200, """{"method":"${exchange.requestMethod}"}""")
            }
            "/with-headers" -> {
                exchange.responseHeaders.add("X-Provider-Trace-Id", "provider-trace-123")
                exchange.respond(201, """{"method":"${exchange.requestMethod}"}""")
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
}
