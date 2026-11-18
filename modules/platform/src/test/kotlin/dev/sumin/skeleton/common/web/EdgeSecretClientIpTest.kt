package dev.sumin.skeleton.common.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper

/**
 * `client-ip.mode=edge-secret`: only a caller that proves the origin secret (`X-Origin-Auth`, compared by SHA-256) may name the client with
 * Cloudflare's headers. Nothing else - no peer address, no trusted-proxies list - is believed.
 */
class EdgeSecretClientIpTest {
    private val secretA = "acme-origin-secret-A-0123456789"
    private val secretB = "acme-origin-secret-B-9876543210"

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun ips(vararg hashes: String) = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.EDGE_SECRET, edgeSecretSha256 = hashes.toList()))

    private fun filter(ips: ClientIps, exempt: Set<String> = emptySet()) = ClientIpFilter(ips, ObjectMapper(), EdgeSecretExemptPaths { exempt })

    private class Outcome(val status: Int, val passed: Boolean, val client: ClientAddress?, val body: String)

    private fun run(filter: ClientIpFilter, ips: ClientIps, path: String = "/api/v1/x", method: String = "GET", build: MockHttpServletRequest.() -> Unit): Outcome {
        val request = MockHttpServletRequest(method, path).apply { remoteAddr = "203.0.113.9"; build() }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        val passed = chain.request != null
        return Outcome(response.status, passed, if (passed) ips.of(request) else null, response.contentAsString)
    }

    @Test
    fun `forwards CF client ip only with valid origin secret`() {
        val ips = ips(sha256(secretA))
        val out = run(filter(ips), ips) {
            addHeader("X-Origin-Auth", secretA)
            addHeader("CF-Connecting-IP", "198.51.100.20")
            addHeader("CF-IPCountry", "kr")
        }
        assertTrue(out.passed)
        assertEquals("198.51.100.20", out.client?.ip)
        assertEquals("KR", out.client?.country)
    }

    @Test
    fun `rejects missing or wrong origin secret with 403`() {
        val ips = ips(sha256(secretA))
        val missing = run(filter(ips), ips) { addHeader("CF-Connecting-IP", "198.51.100.20") }
        val wrong = run(filter(ips), ips) { addHeader("X-Origin-Auth", secretB); addHeader("CF-Connecting-IP", "198.51.100.20") }
        listOf(missing, wrong).forEach {
            assertEquals(403, it.status)
            assertEquals(false, it.passed)
            assertTrue(it.body.contains("COMMON.FORBIDDEN"), it.body)
        }
    }

    @Test
    fun `rejects malformed origin header with 403`() {
        val ips = ips(sha256(secretA))
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            val long = "z".repeat(8193)
            val cases = listOf<Pair<String, MockHttpServletRequest.() -> Unit>>(
                "empty" to { addHeader("X-Origin-Auth", "") },
                "blank" to { addHeader("X-Origin-Auth", "   ") },
                "too long" to { addHeader("X-Origin-Auth", long) },
                "two headers" to { addHeader("X-Origin-Auth", secretA); addHeader("X-Origin-Auth", secretA) },
            )
            cases.forEach { (name, build) ->
                val out = run(filter(ips), ips, build = build)
                assertEquals(403, out.status, name)
                assertEquals(false, out.passed, name)
            }
            val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
            assertTrue(!logged.contains(secretA) && !logged.contains(long) && !logged.contains(sha256(secretA)), logged)
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test
    fun `accepts either of two hashes`() {
        val ips = ips(sha256(secretA), sha256(secretB))
        listOf(secretA, secretB).forEach { secret ->
            val out = run(filter(ips), ips) { addHeader("X-Origin-Auth", secret); addHeader("CF-Connecting-IP", "198.51.100.20") }
            assertTrue(out.passed, secret)
            assertEquals("198.51.100.20", out.client?.ip)
        }
    }

    @Test
    fun `exempt path passes without secret and does not trust CF headers`() {
        val ips = ips(sha256(secretA))
        val drain = "/internal/jobs/drain"
        val out = run(filter(ips, setOf(drain)), ips, path = drain, method = "POST") {
            addHeader("CF-Connecting-IP", "198.51.100.20")
            addHeader("CF-IPCountry", "KR")
        }
        assertTrue(out.passed)
        assertEquals("203.0.113.9", out.client?.ip)
        assertNull(out.client?.country)
        // 정확히 일치하는 POST 경로만 면제한다
        assertEquals(403, run(filter(ips, setOf(drain)), ips, path = drain, method = "GET") {}.status)
        assertEquals(403, run(filter(ips, setOf(drain)), ips, path = "$drain/x", method = "POST") {}.status)
    }

    @Test
    fun `edge-secret without hashes fails startup naming skeleton web client-ip edge-secret-sha256`() {
        val runner = WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebPolicyAutoConfiguration::class.java))
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
        val bad = listOf(
            emptyList(),
            listOf("skeleton.web.client-ip.edge-secret-sha256=not-hex"),
            listOf("skeleton.web.client-ip.edge-secret-sha256=${sha256(secretA).uppercase()}"),
            listOf("skeleton.web.client-ip.edge-secret-sha256=${sha256(secretA)},${sha256(secretB)},${sha256("c")}"),
        )
        bad.forEach { extra ->
            runner.withPropertyValues("skeleton.web.client-ip.mode=edge-secret", *extra.toTypedArray()).run { context ->
                val message = generateSequence(context.startupFailure as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                assertTrue(message.contains("skeleton.web.client-ip.edge-secret-sha256"), message)
                assertTrue(!message.contains("not-hex"), message)
            }
        }
    }

    @Test
    fun `edge-secret does not require trusted-proxies`() {
        val runner = WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebPolicyAutoConfiguration::class.java))
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
        runner.withPropertyValues(
            "skeleton.web.client-ip.mode=edge-secret",
            "skeleton.web.client-ip.trusted-proxies=",
            "skeleton.web.client-ip.edge-secret-sha256=${sha256(secretA)}",
        ).run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(ClientIps::class.java).configured)
        }
    }
}
