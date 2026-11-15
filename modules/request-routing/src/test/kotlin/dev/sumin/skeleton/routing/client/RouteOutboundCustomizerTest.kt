package dev.sumin.skeleton.routing.client

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.common.routing.RouteBaggage
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindow
import dev.sumin.skeleton.routing.RequestRoutingProperties
import dev.sumin.skeleton.routing.RouteTargets
import dev.sumin.skeleton.routing.RoutingErrorCode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.slf4j.MDC
import org.springframework.web.reactive.function.client.WebClient

/** 진짜 HTTP 서버 둘(원래 · 후보)을 두고 나가는 호출이 어디로 가는지, 무엇을 달고 가는지 본다 */
class RouteOutboundCustomizerTest {
    private class Stub {
        val seen = CopyOnWriteArrayList<Seen>()
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                seen += Seen(exchange.requestURI.toString(), exchange.requestHeaders.getFirst("baggage"), exchange.requestHeaders.getFirst("X-Target-Key"))
                val body = "ok".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val origin = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)
    }

    private data class Seen(val uri: String, val baggage: String?, val targetKey: String?)

    private val baseline = Stub()
    private val candidate = Stub()
    private val local = Stub()
    private var openUntil: Instant? = null
    private val now = Instant.parse("2026-11-16T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        MDC.clear()
        listOf(baseline, candidate, local).forEach { it.stop() }
    }

    private fun client(): DefaultExternalHttpClient {
        val routing = RequestRoutingProperties(
            enabled = true,
            services = mapOf(
                "orders" to RequestRoutingProperties.Service(
                    mapOf(
                        "candidate" to RequestRoutingProperties.Target(candidate.origin, headers = mapOf("X-Target-Key" to "secret-1")),
                        "alice" to RequestRoutingProperties.Target(local.origin, local = true),
                    ),
                ),
            ),
        )
        val window = object : LocalRouteWindow {
            override fun openUntil(): Instant? = openUntil
        }
        return DefaultExternalHttpClient(
            webClientBuilder = WebClient.builder(),
            properties = OutboundHttpProperties(
                clients = mapOf(
                    "orders" to OutboundHttpProperties.Client(baseUrl = baseline.origin + "/base"),
                    "toss" to OutboundHttpProperties.Client(baseUrl = baseline.origin + "/toss"),
                ),
            ),
            defaultErrorMapper = DefaultExternalHttpErrorMapper(),
            customizers = listOf(RouteOutboundCustomizer(RouteTargets(routing), window, TimeProvider { now })),
        )
    }

    private fun call(client: String = "orders", path: String = "/items?x=1") =
        client().getBlocking(client, path, String::class.java)

    @Test
    fun routesToTargetKeepingPathAndQuery() {
        MDC.put("route", "orders:candidate")

        call()

        assertEquals(listOf("/base/items?x=1"), candidate.seen.map { it.uri })
        assertEquals(0, baseline.seen.size)
    }

    @Test
    fun propagatesBaggage() {
        MDC.put("route", "orders:candidate")

        call()

        assertEquals("route=orders:candidate", candidate.seen.single().baggage)
    }

    @Test
    fun keepsExistingBaggageMembers() {
        MDC.put("route", "orders:candidate")

        client().getBlocking("orders", "/items", String::class.java) { header("baggage", "a=1") }

        assertEquals("a=1,route=orders:candidate", candidate.seen.single().baggage)
    }

    @Test
    fun noRouteGoesToBaseline() {
        call()

        assertEquals(listOf("/base/items?x=1"), baseline.seen.map { it.uri })
        assertNull(baseline.seen.single().baggage)
        assertEquals(0, candidate.seen.size)
    }

    @Test
    fun routeWithoutThisServiceStaysOnBaselineButKeepsPropagating() {
        MDC.put("route", "payments:candidate")

        call()

        assertEquals(1, baseline.seen.size)
        assertEquals("route=payments:candidate", baseline.seen.single().baggage)
    }

    @Test
    fun otherClientsGetNothing() {
        MDC.put("route", "orders:candidate")

        call(client = "toss", path = "/pay")

        assertEquals(listOf("/toss/pay"), baseline.seen.map { it.uri })
        assertNull(baseline.seen.single().baggage)
        assertEquals(0, candidate.seen.size)
    }

    @Test
    fun addsTargetHeaders() {
        MDC.put("route", "orders:candidate")

        call()

        assertEquals("secret-1", candidate.seen.single().targetKey)
    }

    @Test
    fun localTargetRecheckedAtCallTime() {
        MDC.put("route", "orders:alice")

        val failure = assertFailsWith<ApplicationException> { call() }

        assertEquals(RoutingErrorCode.LOCAL_CLOSED, failure.errorCode)
        assertEquals(0, baseline.seen.size + local.seen.size + candidate.seen.size)
    }

    @Test
    fun localTargetOpenWindowGoesThrough() {
        openUntil = now.plusSeconds(60)
        MDC.put("route", "orders:alice")

        call()

        assertEquals(1, local.seen.size)
        assertEquals(0, baseline.seen.size)
    }

    @Test
    fun restoredRouteReachesTarget() {
        RouteBaggage.restore("orders:candidate").use { call() }

        assertEquals(1, candidate.seen.size)
        assertEquals(0, baseline.seen.size)
    }
}
