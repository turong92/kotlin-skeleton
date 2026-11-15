package dev.sumin.skeleton.alert.client

import dev.sumin.skeleton.alert.AlertAutoConfiguration
import dev.sumin.skeleton.alert.AlertProperties
import dev.sumin.skeleton.common.GlobalExceptionHandler
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.PublicEndpoint
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import dev.sumin.skeleton.common.web.WebProperties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ClientErrorWebTest {
    private val received = CopyOnWriteArrayList<ClientErrorReport>()
    private val settings = AlertProperties.ClientErrors(enabled = true, perClient = 2, maxBodyBytes = 300)
    private val intake = ClientErrorIntake(
        settings, InMemoryFixedWindowRateLimitStore(), TimeProvider.systemUtc(),
        ClientErrorSurge(dev.sumin.skeleton.alert.OwnerAlerts.NONE, settings.surge, TimeProvider.systemUtc()), sink = { received += it },
    )
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(ClientErrorController(intake, ClientIps()))
        .setControllerAdvice(GlobalExceptionHandler()).build()

    private fun send(body: String, type: MediaType = MediaType.APPLICATION_JSON, ip: String = "198.51.100.1") =
        mvc.perform(post("/api/v1/client-errors").contentType(type).content(body).with { it.remoteAddr = ip; it })

    @Test
    fun `a report is answered with 204 and no body`() {
        send("""{"message":"boom"}""").andExpect(status().isNoContent)
        assertEquals("boom", received.single().message)
    }

    @Test
    fun `text plain is accepted too, because sendBeacon with a string cannot set a JSON content type`() {
        send("""{"message":"beacon"}""", MediaType.TEXT_PLAIN).andExpect(status().isNoContent)
        assertEquals("beacon", received.single().message)
    }

    @Test
    fun `too big is 413, garbage is 400, a flood is 429 with the standard error body`() {
        send("""{"message":"${"m".repeat(400)}"}""", ip = "198.51.100.2").andExpect(status().isContentTooLarge).andExpect(jsonPath("$.code").value("CLIENT_ERROR.TOO_LARGE"))
        send("nope", ip = "198.51.100.3").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CLIENT_ERROR.INVALID"))
        send("""{"message":"a"}""", ip = "198.51.100.4").andExpect(status().isNoContent)
        send("""{"message":"b"}""", ip = "198.51.100.4").andExpect(status().isNoContent)
        send("""{"message":"c"}""", ip = "198.51.100.4").andExpect(status().isTooManyRequests).andExpect(jsonPath("$.data.retryAfterSeconds").exists())
    }

    @Test
    fun `the default sink writes one structured log line with the fields as key values`() {
        val logger = LoggerFactory.getLogger("skeleton.client-error") as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            ClientErrorLog.write(
                ClientErrorReport(
                    platform = "web", source = "boundary", name = "TypeError", message = "boom", stack = "at x", componentStack = null,
                    route = "/orders", release = "1.0.0", clientTraceId = "0af7651916cd43dd8448eb211c80319c", count = 2, userAgent = "UA",
                ),
            )
        } finally {
            logger.detachAppender(appender)
        }
        val event = appender.list.single()
        assertTrue(event.formattedMessage.contains("boom"), event.formattedMessage)
        val pairs = event.keyValuePairs.associate { it.key to it.value.toString() }
        assertEquals("web", pairs["clientPlatform"])
        assertEquals("0af7651916cd43dd8448eb211c80319c", pairs["clientTraceId"])
        assertEquals("/orders", pairs["clientRoute"])
        assertEquals("1.0.0", pairs["clientRelease"])
        assertEquals("2", pairs["clientCount"])
        assertEquals("at x", pairs["clientStack"])
    }

    private val runner = WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AlertAutoConfiguration::class.java, ClientErrorAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })

    @Test
    fun `it is off unless switched on - no route, no public opening`() {
        runner.run { context ->
            assertTrue(context.getBeansOfType(ClientErrorController::class.java).isEmpty())
            assertTrue(context.getBeansOfType(PublicEndpointContributor::class.java).isEmpty())
        }
    }

    @Test
    fun `switched on it brings the controller and opens only that one path to anonymous callers`() {
        runner.withPropertyValues("skeleton.alert.client-errors.enabled=true").run { context ->
            assertEquals(1, context.getBeansOfType(ClientErrorController::class.java).size)
            val base = PublicEndpointRegistry.default().endpoints
            val opened = PublicEndpointRegistry.from(WebProperties(), context.getBeansOfType(PublicEndpointContributor::class.java).values).endpoints - base.toSet()
            assertEquals(listOf(PublicEndpoint.method("POST", "/api/v1/client-errors")), opened)
        }
    }

    @Test
    fun `switching the whole alert module off removes it as well`() {
        runner.withPropertyValues("skeleton.alert.enabled=false", "skeleton.alert.client-errors.enabled=true").run { context ->
            assertTrue(context.getBeansOfType(ClientErrorController::class.java).isEmpty())
        }
    }
}
