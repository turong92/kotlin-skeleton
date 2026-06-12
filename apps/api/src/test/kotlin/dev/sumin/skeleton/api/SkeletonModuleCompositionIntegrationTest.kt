package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SkeletonModuleCompositionIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `module catalog exposes composed runtime modules`() {
        mockMvc.get("/api/v1/skeleton/modules") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.values[?(@.id == 'platform')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'auth')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'redis-core')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'redis-lock')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'notification')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'notification-sse')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.values[?(@.id == 'storage-s3')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'payment-toss')].status") { value(hasItem("DISABLED")) }
            jsonPath("$.values[?(@.id == 'event-kafka')].status") { value(hasItem("ACTIVE")) }
            jsonPath("$.meta.traceId") { isNotEmpty() }
        }
    }

    @Test
    fun `module smoke endpoints exercise reusable module contracts`() {
        val token = loginAccessToken()

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
}
