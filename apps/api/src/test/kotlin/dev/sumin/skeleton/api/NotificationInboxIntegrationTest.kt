package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

@SpringBootTest(properties = ["skeleton.notification.slack.notification-events=false"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class NotificationInboxIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        token = loginAccessToken()
    }

    @Test
    fun `published notification can be listed and marked read by recipient`() {
        val topic = "inbox-${UUID.randomUUID()}"
        val eventId = publishNotificationFor(accountId = "acc_user", topic = topic)

        mockMvc.get("/api/v1/notifications") {
            header("Authorization", "Bearer $token")
            param("page", "0")
            param("size", "10")
            param("unreadOnly", "true")
            param("topic", topic)
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.values", hasSize<Any>(1))
            jsonPath("$.values[0].eventId") { value(eventId) }
            jsonPath("$.values[0].readAt") { doesNotExist() }
            jsonPath("$.pagination.totalElements") { value(1) }
        }

        mockMvc.patch("/api/v1/notifications/{eventId}/read", eventId) {
            header("Authorization", "Bearer $token")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.eventId") { value(eventId) }
            jsonPath("$.value.readAt") { isNotEmpty() }
        }

        mockMvc.get("/api/v1/notifications") {
            header("Authorization", "Bearer $token")
            param("unreadOnly", "true")
            param("topic", topic)
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.values", hasSize<Any>(0))
            jsonPath("$.pagination.totalElements") { value(0) }
        }

        mockMvc.get("/api/v1/notifications") {
            header("Authorization", "Bearer $token")
            param("topic", topic)
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.values", hasSize<Any>(1))
            jsonPath("$.values[0].eventId") { value(eventId) }
            jsonPath("$.values[0].readAt") { isNotEmpty() }
        }
    }

    private fun publishNotificationFor(
        accountId: String,
        topic: String,
    ): String {
        val response = mockMvc.post("/api/v1/skeleton/notifications") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """
                {
                  "topic": "$topic",
                  "type": "inbox-smoke",
                  "recipientIds": ["$accountId"],
                  "title": "Inbox smoke",
                  "message": "stored notification",
                  "payload": {
                    "source": "integration-test"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        return JsonPath.read<String>(response, "$.value.eventId").also {
            assertTrue(it.isNotBlank())
        }
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val accessToken = JsonPath.read<String>(response, "$.value.accessToken")
        assertTrue(accessToken.isNotBlank())
        return accessToken
    }
}
