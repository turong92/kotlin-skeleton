package dev.sumin.skeleton.routing.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.ClosedLocalRouteWindow
import dev.sumin.skeleton.routing.RequestRoutingProperties
import dev.sumin.skeleton.routing.RouteTargets
import dev.sumin.skeleton.routing.RoutingPermissions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.User
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/** 입구 서비스(`service=api`, 원래 서버)가 직원의 `route=api:<대상>` 요청을 통째로 대상에 넘긴다 — 진짜 서버 둘(앱 + JDK HttpServer 후보) */
@SpringBootTest(
    classes = [RouteProxyTest.App::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RouteProxyTest(@Autowired @LocalServerPort private val port: Int) {
    @Configuration
    @EnableAutoConfiguration
    @EnableWebSecurity
    class App {
        @Bean
        fun chain(http: HttpSecurity): SecurityFilterChain {
            http.csrf { it.disable() }
            http.httpBasic { }
            http.authorizeHttpRequests { it.anyRequest().permitAll() }
            return http.build()
        }

        @Bean
        fun users() = InMemoryUserDetailsManager(
            User.withUsername("alice").password("{noop}pw").roles("ADMIN").build(),
            User.withUsername("bob").password("{noop}pw").roles("USER").build(),
        )

        @Bean
        fun access() = object : OperatorAccess {
            override fun permissionsOf(ref: OperatorRef): Set<String> = if ("ADMIN" in ref.tokenRoles) setOf(RoutingPermissions.USE) else emptySet()
        }

        @Bean
        fun time(): TimeProvider = TimeProvider.systemUtc()

        @Bean
        fun echo() = Echo()
    }

    @RestController
    class Echo {
        @RequestMapping("/api/v1/echo")
        fun echo(): String = "original"
    }

    private class Seen(val method: String, val uri: String, val body: String, val headers: Map<String, List<String>>)

    companion object {
        private val seen = CopyOnWriteArrayList<Seen>()
        private val candidate: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                seen += Seen(exchange.requestMethod, exchange.requestURI.toString(), exchange.requestBody.readBytes().decodeToString(), exchange.requestHeaders.toMap())
                if (exchange.requestURI.path == "/events") sse(exchange) else reply(exchange)
            }
            start()
        }

        private fun reply(exchange: HttpExchange) {
            val bytes = """{"from":"candidate"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Connection", "keep-alive")
            exchange.responseHeaders.add("X-Candidate", "yes")
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private fun sse(exchange: HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use {
                it.write("data: first\n\n".toByteArray())
                it.flush()
                Thread.sleep(1500)
                it.write("data: second\n\n".toByteArray())
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val origin = "http://127.0.0.1:${candidate.address.port}"
            registry.add("skeleton.routing.enabled") { "true" }
            registry.add("skeleton.routing.service") { "api" }
            registry.add("skeleton.routing.services.api.targets.candidate.url") { origin }
            registry.add("skeleton.routing.services.api.targets.alice.url") { origin }
            registry.add("skeleton.routing.services.api.targets.alice.local") { "true" }
            registry.add("skeleton.routing.services.api.targets.dead.url") { "http://127.0.0.1:1" }
            registry.add("skeleton.routing.proxy.timeout") { "5s" }
        }
    }

    private val client = HttpClient.newHttpClient()

    @AfterTest
    fun clear() {
        seen.clear()
        SecurityContextHolder.clearContext()
    }

    private fun basic(user: String) = "Basic " + Base64.getEncoder().encodeToString("$user:pw".toByteArray())

    private fun request(path: String, user: String, route: String, configure: HttpRequest.Builder.() -> Unit = {}): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Authorization", basic(user))
            .header("baggage", "userId=7,route=$route")
            .apply(configure)

    @Test
    fun forwardsStaffRequestToSelfTarget() {
        val response = client.send(
            request("/api/v1/echo?x=1", "alice", "api:candidate") { POST(HttpRequest.BodyPublishers.ofString("""{"a":1}""")) }.build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(201, response.statusCode())
        assertEquals("""{"from":"candidate"}""", response.body())
        assertEquals("yes", response.headers().firstValue("X-Candidate").orElse(null))
        val call = seen.single()
        assertEquals("POST", call.method)
        assertEquals("/api/v1/echo?x=1", call.uri)
        assertEquals("""{"a":1}""", call.body)
        assertEquals(basic("alice"), call.headers.entries.first { it.key.equals("Authorization", true) }.value.single())
        assertEquals("1.1 request-routing", call.headers.entries.first { it.key.equals("Via", true) }.value.single())
        assertEquals("userId=7,route=api:candidate", call.headers.entries.first { it.key.equals("baggage", true) }.value.single())
    }

    @Test
    fun customerIsNeverForwarded() {
        val response = client.send(request("/api/v1/echo", "bob", "api:candidate").GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals("original", response.body())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun loopIs508() {
        val response = client.send(
            request("/api/v1/echo", "alice", "api:candidate") { header("Via", "1.1 request-routing") }.GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(508, response.statusCode())
        assertTrue("ROUTING.LOOP" in response.body())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun unreachableIs502() {
        val response = client.send(request("/api/v1/echo", "alice", "api:dead").GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(502, response.statusCode())
        assertTrue("ROUTING.TARGET_UNREACHABLE" in response.body())
    }

    @Test
    fun localSelfTargetNeedsOpenWindow() {
        val response = client.send(request("/api/v1/echo", "alice", "api:alice").GET().build(), HttpResponse.BodyHandlers.ofString())

        assertEquals(403, response.statusCode())
        assertTrue("ROUTING.LOCAL_CLOSED" in response.body())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun streamsEventStreamLineByLine() {
        val started = System.nanoTime()
        val response = client.send(request("/events", "alice", "api:candidate").GET().build(), HttpResponse.BodyHandlers.ofInputStream())

        BufferedReader(InputStreamReader(response.body())).use { reader ->
            assertEquals("data: first", reader.readLine())
            val firstLineMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(firstLineMillis < 1000, "first line took ${firstLineMillis}ms")
            reader.readLine()
            assertEquals("data: second", reader.readLine())
        }
    }

    @Test
    fun doesNotForwardOnTargetInstance() {
        val properties = RequestRoutingProperties(
            enabled = true,
            service = "api",
            instance = "candidate",
            services = mapOf("api" to RequestRoutingProperties.Service(mapOf("candidate" to RequestRoutingProperties.Target("http://127.0.0.1:1")))),
        )
        val access = object : OperatorAccess {
            override fun permissionsOf(ref: OperatorRef): Set<String> = setOf(RoutingPermissions.USE)
        }
        val filter = RouteInboundFilter(
            properties, RouteTargets(properties), ClosedLocalRouteWindow, access, TimeProvider.systemUtc(),
            RoutingErrorWriter(JsonMapper.builder().build()), RouteProxy(client, Duration.ofSeconds(1)),
        )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated("alice", "pw", listOf(SimpleGrantedAuthority("ROLE_ADMIN")))
        val chain = MockFilterChain()
        val response = MockHttpServletResponse()

        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/echo").apply { addHeader("baggage", "route=api:candidate") }, response, chain)

        assertEquals(200, response.status)
        assertTrue(chain.request != null, "the request was handled here, not forwarded")
    }
}
