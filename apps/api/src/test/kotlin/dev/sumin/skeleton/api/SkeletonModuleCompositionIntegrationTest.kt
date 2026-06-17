package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.async.notification.AsyncNotificationExceptionHandler
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import dev.sumin.skeleton.notification.websocket.NotificationWebSocketTokenVerifier
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertTrue

@SpringBootTest(properties = ["skeleton.notification.slack.notification-events=false"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SkeletonModuleCompositionIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var webSocketTokenVerifiers: List<NotificationWebSocketTokenVerifier>

    @Autowired
    private lateinit var notificationSubscriptionRegistry: NotificationSubscriptionRegistry

    @Autowired
    private lateinit var asyncExceptionHandlers: List<AsyncUncaughtExceptionHandler>

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `module composition wires websocket token verifier from auth jwt`() {
        assertTrue(webSocketTokenVerifiers.isNotEmpty())
    }

    @Test
    fun `module catalog exposes composed runtime modules`() {
        mockMvc.get("/api/v1/skeleton/modules") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.values[?(@.id == 'platform')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'async')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'async-notification')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'json')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'auth')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'redis-core')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'redis-lock')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'persistence-jpa')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'persistence-jdbc')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'notification')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'notification-sse')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'notification-websocket')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'storage-s3')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'payment-toss')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'event-kafka')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.meta.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `module smoke endpoints exercise reusable module contracts`(output: CapturedOutput) {
        val token = loginAccessToken()
        val fixedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"

        mockMvc.get("/api/v1/skeleton/redis/key") {
            header("Authorization", "Bearer $token")
            param("value", "orders:1")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.key") { value("kotlin-skeleton:orders:1") }
        }

        mockMvc.post("/api/v1/skeleton/storage/validate") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"fileName":"avatar.png","contentType":"image/png","sizeBytes":1024}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.valid") { value(true) }
            jsonPath("$.value.errors.length()") { value(0) }
        }

        mockMvc.get("/api/v1/skeleton/storage/public-url") {
            header("Authorization", "Bearer $token")
            param("key", "images/cat.png")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.key") { value("images/cat.png") }
            jsonPath("$.value.available") { value(true) }
            jsonPath("$.value.publicUrl") { value("https://cdn.example.test/images/cat.png") }
        }

        mockMvc.post("/api/v1/skeleton/notifications") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"topic":"runs","type":"probe","message":"composition smoke"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.topic") { value("runs") }
            jsonPath("$.value.type") { value("probe") }
        }

        mockMvc.get("/api/v1/skeleton/payments/route") {
            header("Authorization", "Bearer $token")
            param("amount", "1000")
            param("currency", "KRW")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.provider") { value("toss") }
            jsonPath("$.value.available") { value(false) }
            jsonPath("$.value.source") { value("configuration") }
        }

        mockMvc.get("/api/v1/skeleton/async/probe") {
            header("Authorization", "Bearer $token")
            header("traceparent", "00-$fixedTraceId-00f067aa0ba902b7-01")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.taskGroup.total") { value(1) }
            jsonPath("$.value.taskGroup.succeeded[0]") { value("context-propagation") }
            jsonPath("$.value.task.traceId") { value(fixedTraceId) }
            jsonPath("$.value.task.runId") { value(startsWith("probe-")) }
            jsonPath("$.value.task.accountId") { value("acc_user") }
            jsonPath("$.value.task.threadName") { value(startsWith("skeleton-async-")) }
        }

        assertTrue(output.all.contains("skeleton async probe completed"))
        assertTrue(output.all.contains("traceId=$fixedTraceId"))
        assertTrue(output.all.contains("runId=probe-"))
        assertTrue(output.all.contains("accountId=acc_user"))
        assertTrue(
            asyncExceptionHandlers.any { it is AsyncNotificationExceptionHandler },
            "Async exception handlers: ${asyncExceptionHandlers.map { it::class.qualifiedName }}",
        )
        val asyncConfigurerNames = applicationContext.getBeanNamesForType(AsyncConfigurer::class.java).toList()
        assertTrue(
            asyncConfigurerNames.size == 1,
            "Async configurers: $asyncConfigurerNames",
        )

        val asyncEvents = Collections.synchronizedList(mutableListOf<NotificationEvent>())
        val asyncEventLatch = CountDownLatch(1)
        notificationSubscriptionRegistry.subscribe(
            topics = setOf("async.exception"),
            subscriber = NotificationSubscriber { event ->
                asyncEvents += event
                asyncEventLatch.countDown()
            },
        ).use {
            mockMvc.post("/api/v1/skeleton/async/fail") {
                header("Authorization", "Bearer $token")
                header("traceparent", "00-$fixedTraceId-00f067aa0ba902b7-01")
                accept = MediaType.APPLICATION_JSON
            }.andExpect {
                status { isOk() }
                jsonPath("$.meta.traceId") { value(fixedTraceId) }
            }

            assertTrue(asyncEventLatch.await(3, TimeUnit.SECONDS))
        }
        val asyncFailureEvent = asyncEvents.single()
        assertTrue(asyncFailureEvent.type == "async-exception")
        assertTrue(asyncFailureEvent.payload["traceId"] == fixedTraceId)
        assertTrue(asyncFailureEvent.payload["runId"].toString().startsWith("probe-"))
        assertTrue(asyncFailureEvent.payload["accountId"] == "acc_user")
    }

    @Test
    fun `notification smoke endpoint publishes demo event to subscribers`() {
        val token = loginAccessToken()
        val events = Collections.synchronizedList(mutableListOf<NotificationEvent>())
        val latch = CountDownLatch(1)

        notificationSubscriptionRegistry.subscribe(
            topics = setOf("demo"),
            subscriber = NotificationSubscriber { event ->
                events += event
                latch.countDown()
            },
        ).use {
            mockMvc.post("/api/v1/skeleton/notifications") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                accept = MediaType.APPLICATION_JSON
                content = """
                    {
                      "topic": "demo",
                      "type": "frontend-smoke",
                      "severity": "INFO",
                      "title": "Frontend smoke",
                      "message": "React skeleton workbench ping",
                      "payload": {
                        "source": "react-skeleton",
                        "userId": "acc_user"
                      }
                    }
                """.trimIndent()
            }.andExpect {
                status { isOk() }
                jsonPath("$.value.topic") { value("demo") }
                jsonPath("$.value.type") { value("frontend-smoke") }
                jsonPath("$.value.deliveredSubscribers") { value(1) }
                jsonPath("$.meta.traceId") { isNotEmpty() }
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS))
        }

        val event = events.single()
        assertTrue(event.topic == "demo")
        assertTrue(event.type == "frontend-smoke")
        assertTrue(event.payload["source"] == "react-skeleton")
        assertTrue(event.payload["userId"] == "acc_user")
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val token = JsonPath.read<String>(response, "$.value.accessToken")
        assertTrue(token.isNotBlank())
        return token
    }

    @TestConfiguration
    class StoragePublicUrlTestConfiguration {
        @Bean
        fun testStoragePublicUrlResolver(): StoragePublicUrlResolver =
            StoragePublicUrlResolver { key -> URI.create("https://cdn.example.test/${key.value}") }
    }
}
