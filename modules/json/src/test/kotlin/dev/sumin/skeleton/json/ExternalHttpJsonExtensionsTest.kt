package dev.sumin.skeleton.json

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpEndpoint
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient

class ExternalHttpJsonExtensionsTest {
    private lateinit var server: HttpServer
    private lateinit var client: DefaultExternalHttpClient
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val codec = JacksonJsonCodec()

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
                    "json-provider" to OutboundHttpProperties.Client(
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
        server.stop(0)
    }

    @Test
    fun `get json response returns raw json document with upstream trace`() {
        val response = client.getJsonResponse(
            ExternalHttpEndpoint("json-provider", "/raw"),
        ).block()

        assertNotNull(response)
        assertEquals("provider", response.body.textAt("/source"))
        assertEquals("request-123", response.trace.traceId)
    }

    @Test
    fun `post json response sends document as application json`() {
        val response = client.postJsonResponse(
            endpoint = ExternalHttpEndpoint("json-provider", "/echo"),
            body = codec.parse("""{"b":2,"a":1}"""),
        ).block()

        assertNotNull(response)
        assertEquals(2, response.body.requiredAt("/received/b").intValue())
        val request = requests.single { it.path == "/echo" }
        assertEquals("application/json", request.headers["content-type"]?.single()?.substringBefore(";"))
        assertEquals("""{"b":2,"a":1}""", request.body)
    }

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        requests += RecordedRequest(
            path = exchange.requestURI.path,
            headers = exchange.requestHeaders.mapKeys { it.key.lowercase() },
            body = body,
        )

        when (exchange.requestURI.path) {
            "/raw" -> {
                exchange.responseHeaders.add("X-Request-Id", "request-123")
                exchange.respond(200, """{"source":"provider","nested":{"value":1}}""")
            }
            "/echo" -> exchange.respond(200, """{"received":$body}""")
            else -> exchange.respond(404, """{"message":"not found"}""")
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json")
        sendResponseHeaders(status, body.toByteArray().size.toLong())
        responseBody.use { output -> output.write(body.toByteArray()) }
    }

    data class RecordedRequest(
        val path: String,
        val headers: Map<String, List<String>>,
        val body: String,
    )
}
