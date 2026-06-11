package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class IdempotencyIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `idempotent operation requires Idempotency-Key`() {
        mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"sample"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(400) }
            jsonPath("$.title") { value("Missing Idempotency-Key") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `same Idempotency-Key and request body replays first response`() {
        val token = loginAccessToken()
        val first = mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "create-item-1")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"sample"}"""
        }.andExpect {
            status { isCreated() }
            header { string("X-Idempotency-Replayed", "false") }
            header { string("Location", "http://localhost/api/v1/examples/items/item-1") }
            jsonPath("$.value.id") { value("item-1") }
            jsonPath("$.value.name") { value("sample") }
        }.andReturn().response.contentAsString

        val second = mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "create-item-1")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"sample"}"""
        }.andExpect {
            status { isCreated() }
            header { string("X-Idempotency-Replayed", "true") }
            header { string("Location", "http://localhost/api/v1/examples/items/item-1") }
            jsonPath("$.value.id") { value("item-1") }
            jsonPath("$.value.name") { value("sample") }
        }.andReturn().response.contentAsString

        assertEquals(first, second)
    }

    @Test
    fun `same Idempotency-Key with different request body returns conflict`() {
        val token = loginAccessToken()
        mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "create-item-conflict")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"sample"}"""
        }.andExpect {
            status { isCreated() }
        }

        mockMvc.post("/api/v1/examples/items") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", "create-item-conflict")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"name":"other"}"""
        }.andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(409) }
            jsonPath("$.title") { value("Idempotency key conflict") }
        }
    }

    @Test
    fun `non idempotent operation still works without Idempotency-Key`() {
        mockMvc.post("/api/v1/examples/jobs") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.value.jobId") { value("job-1") }
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

        return JsonPath.read(response, "$.value.accessToken")
    }
}
