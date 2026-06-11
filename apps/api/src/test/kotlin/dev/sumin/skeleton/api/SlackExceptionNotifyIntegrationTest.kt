package dev.sumin.skeleton.api

import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.notification.slack.SlackAlert
import dev.sumin.skeleton.notification.slack.SlackAlertSender
import dev.sumin.skeleton.notification.slack.SlackExceptionNotify
import java.util.Collections
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest(
    properties = [
        "skeleton.notification.slack.enabled=true",
        "skeleton.notification.slack.webhook-url=https://hooks.slack.example/test",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SlackExceptionNotifyIntegrationTest.TestConfig::class)
class SlackExceptionNotifyIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @BeforeTest
    fun setUp() {
        CapturingSlackAlertSender.clear()
    }

    @Test
    fun `annotated controller exception creates Slack alert with request trace`() {
        val result = mockMvc.get("/api/v1/test/slack-exception") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isInternalServerError() }
            header { exists(TraceIdFilter.HEADER_TRACE_ID) }
            header { exists(TraceIdFilter.HEADER_SPAN_ID) }
        }.andReturn()

        val alert = CapturingSlackAlertSender.alerts.single()
        assertEquals("Integration failure", alert.title)
        assertEquals("boom", alert.message)
        assertEquals("test", alert.route)
        assertEquals(result.response.getHeader(TraceIdFilter.HEADER_TRACE_ID), alert.trace.traceId)
        assertEquals(result.response.getHeader(TraceIdFilter.HEADER_SPAN_ID), alert.trace.spanId)
        assertTrue(alert.trace.traceId?.matches(Regex("[0-9a-f]{32}")) == true)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestConfig {
        @Bean
        fun slackTestPublicEndpoint(): PublicEndpointContributor =
            PublicEndpointContributor { registry ->
                registry.add("GET", "/api/v1/test/slack-exception")
            }

        @Bean
        fun slackTestController(): SlackTestController = SlackTestController()

        @Bean
        @Primary
        fun slackAlertSender(): SlackAlertSender = CapturingSlackAlertSender
    }

    @RestController
    class SlackTestController {
        @GetMapping("/api/v1/test/slack-exception")
        @SlackExceptionNotify(route = "test", title = "Integration failure")
        fun fail(): Nothing = error("boom")
    }

    object CapturingSlackAlertSender : SlackAlertSender {
        val alerts: MutableList<SlackAlert> = Collections.synchronizedList(mutableListOf())

        fun clear() {
            alerts.clear()
        }

        override fun send(alert: SlackAlert) {
            alerts += alert
        }
    }
}
