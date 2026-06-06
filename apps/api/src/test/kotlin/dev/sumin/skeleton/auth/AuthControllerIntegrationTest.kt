package dev.sumin.skeleton.auth

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.common.TraceIdFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET me without authentication returns ApiError with trace context`() {
        val traceId = "1234567890abcdef1234567890abcdef"
        val parentSpanId = "abcdef1234567890"
        mockMvc.get("/api/v1/auth/me") {
            header(TraceIdFilter.HEADER_TRACEPARENT, "00-$traceId-$parentSpanId-01")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.title") { isNotEmpty() }
            jsonPath("$.traceId") { value(traceId) }
            jsonPath("$.spanId") { isNotEmpty() }
            jsonPath("$.timestamp") { isNotEmpty() }
        }
    }

    @Test
    fun `POST login with seed user email and password returns bearer token and principal`() {
        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.accessToken") { isNotEmpty() }
            jsonPath("$.value.tokenType") { value("Bearer") }
            jsonPath("$.value.expiresAt") { isNotEmpty() }
            jsonPath("$.value.principal.accountId") { value("acc_user") }
            jsonPath("$.value.principal.username") { value("user") }
            jsonPath("$.value.principal.email") { value("user@example.com") }
            jsonPath("$.value.principal.roles[0]") { value("USER") }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `GET me with login bearer token returns same principal`() {
        val loginResponse = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val accessToken = JsonPath.read<String>(loginResponse, "$.value.accessToken")
        val accountId = JsonPath.read<String>(loginResponse, "$.value.principal.accountId")

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $accessToken")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.value.accountId") { value(accountId) }
            jsonPath("$.value.username") { value("user") }
            jsonPath("$.value.email") { value("user@example.com") }
            jsonPath("$.value.roles[0]") { value("USER") }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `GET hello remains public`() {
        val result = mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.value.message") { value("Hello from Kotlin backend!") }
        }.andReturn()

        val traceId = result.response.getHeader(TraceIdFilter.HEADER_TRACE_ID).orEmpty()
        val spanId = result.response.getHeader(TraceIdFilter.HEADER_SPAN_ID).orEmpty()
        assertTrue(traceId.matches(Regex("[0-9a-f]{32}")))
        assertTrue(spanId.matches(Regex("[0-9a-f]{16}")))
        assertEquals("00-$traceId-$spanId-01", result.response.getHeader(TraceIdFilter.HEADER_TRACEPARENT))
    }
}
