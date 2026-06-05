package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.common.TraceIdFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `/api/v1/hello` 통합 테스트.
 *
 * - Testcontainers 가 실제 MySQL 컨테이너 띄움 (Flyway 마이그레이션 포함 검증)
 * - MockMvc 로 HTTP 레이어까지 왕복
 * - traceId 전파 검증: W3C traceparent 헤더를 보내면 traceId 를 승계하는지
 *
 * 이 테스트 하나로:
 * 1. Spring Context 로드
 * 2. Flyway 마이그레이션
 * 3. 컨트롤러 라우팅
 * 4. TraceIdFilter 동작
 * 모두 확인됨. 새 앱 만들 때 참고용 템플릿.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HelloControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `GET hello returns message + auto-generated traceId header`() {
        val result = mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.message") { value("Hello from Kotlin backend!") }
            jsonPath("$.timestamp") { isNotEmpty() }
            header { exists(TraceIdFilter.HEADER_TRACE_ID) }
            header { exists(TraceIdFilter.HEADER_SPAN_ID) }
            header { exists(TraceIdFilter.HEADER_TRACEPARENT) }
        }.andReturn()

        val traceId = result.response.getHeader(TraceIdFilter.HEADER_TRACE_ID).orEmpty()
        val spanId = result.response.getHeader(TraceIdFilter.HEADER_SPAN_ID).orEmpty()
        assertTrue(traceId.matches(Regex("[0-9a-f]{32}")))
        assertTrue(spanId.matches(Regex("[0-9a-f]{16}")))
        assertEquals("00-$traceId-$spanId-01", result.response.getHeader(TraceIdFilter.HEADER_TRACEPARENT))
    }

    @Test
    fun `GET hello propagates W3C traceparent traceId and creates server spanId`() {
        val clientTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val clientSpanId = "00f067aa0ba902b7"
        mockMvc.get("/api/v1/hello") {
            header(TraceIdFilter.HEADER_TRACEPARENT, "00-$clientTraceId-$clientSpanId-01")
        }.andExpect {
            status { isOk() }
            header {
                string(TraceIdFilter.HEADER_TRACE_ID, clientTraceId)
                exists(TraceIdFilter.HEADER_SPAN_ID)
                exists(TraceIdFilter.HEADER_TRACEPARENT)
            }
        }.andReturn().response.let { response ->
            val serverSpanId = response.getHeader(TraceIdFilter.HEADER_SPAN_ID).orEmpty()
            assertTrue(serverSpanId.matches(Regex("[0-9a-f]{16}")))
            assertNotEquals(clientSpanId, serverSpanId)
            assertEquals("00-$clientTraceId-$serverSpanId-01", response.getHeader(TraceIdFilter.HEADER_TRACEPARENT))
        }
    }

    @Test
    fun `unmapped path returns standardized ApiError with traceId`() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val parentSpanId = "abcdef0123456789"
        val accessToken = loginAccessToken()
        val result = mockMvc.get("/api/v1/does-not-exist") {
            header(TraceIdFilter.HEADER_TRACEPARENT, "00-$traceId-$parentSpanId-01")
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.title") { isNotEmpty() }
            jsonPath("$.traceId") { value(traceId) }
            jsonPath("$.spanId") { isNotEmpty() }
            jsonPath("$.timestamp") { isNotEmpty() }
        }.andReturn()

        val spanId = result.response.getHeader(TraceIdFilter.HEADER_SPAN_ID).orEmpty()
        assertTrue(spanId.matches(Regex("[0-9a-f]{16}")))
        assertEquals(traceId, result.response.getHeader(TraceIdFilter.HEADER_TRACE_ID))
        assertEquals("00-$traceId-$spanId-01", result.response.getHeader(TraceIdFilter.HEADER_TRACEPARENT))
    }

    private fun loginAccessToken(): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"email":"user@example.com","password":"password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        return JsonPath.read(response, "$.accessToken")
    }
}
