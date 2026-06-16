package dev.sumin.skeleton.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.common.TraceIdFilter
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
@Import(TestcontainersConfiguration::class, HelloControllerIntegrationTest.ErrorProbeConfiguration::class)
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
            jsonPath("$.value.message") { value("Hello from Kotlin backend!") }
            jsonPath("$.value.timestamp") { isNotEmpty() }
            jsonPath("$.meta.traceId") { isNotEmpty() }
            jsonPath("$.meta.spanId") { isNotEmpty() }
            jsonPath("$.meta.timestamp") { isNotEmpty() }
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
            jsonPath("$.code") { value("COMMON.NOT_FOUND") }
            jsonPath("$.title") { isNotEmpty() }
            jsonPath("$.traceId") { value(traceId) }
            jsonPath("$.spanId") { isNotEmpty() }
            jsonPath("$.timestamp") { isNotEmpty() }
        }.andReturn()

        assertFalse(result.response.contentAsString.contains("\"type\""))
        val spanId = result.response.getHeader(TraceIdFilter.HEADER_SPAN_ID).orEmpty()
        assertTrue(spanId.matches(Regex("[0-9a-f]{16}")))
        assertEquals(traceId, result.response.getHeader(TraceIdFilter.HEADER_TRACE_ID))
        assertEquals("00-$traceId-$spanId-01", result.response.getHeader(TraceIdFilter.HEADER_TRACEPARENT))
    }

    @Test
    fun `unhandled exception returns standard internal error code without leaking raw exception message`() {
        val result = mockMvc.get("/api/v1/test/unhandled-error") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isInternalServerError() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(500) }
            jsonPath("$.code") { value("COMMON.INTERNAL_SERVER_ERROR") }
            jsonPath("$.title") { value("Internal server error") }
            jsonPath("$.detail") { value("An unexpected error occurred. Use traceId for investigation.") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }.andReturn()

        assertFalse(result.response.contentAsString.contains("raw-provider-secret"))
        assertFalse(result.response.contentAsString.contains("\"type\""))
    }

    @Test
    fun `data integrity exception returns standard conflict error without raw database message`() {
        val result = mockMvc.get("/api/v1/test/data-integrity-error") {
            header("Authorization", "Bearer ${loginAccessToken()}")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(409) }
            jsonPath("$.code") { value("COMMON.DATA_INTEGRITY_VIOLATION") }
            jsonPath("$.title") { value("Data integrity violation") }
            jsonPath("$.detail") { value("Request conflicts with existing data.") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }.andReturn()

        assertFalse(result.response.contentAsString.contains("Duplicate entry"))
        assertFalse(result.response.contentAsString.contains("secret_unique_key"))
        assertFalse(result.response.contentAsString.contains("\"type\""))
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

    @TestConfiguration
    class ErrorProbeConfiguration {
        @Bean
        fun errorProbeController(): ErrorProbeController = ErrorProbeController()
    }

    @RestController
    class ErrorProbeController {
        @GetMapping("/api/v1/test/unhandled-error")
        fun unhandled(): Nothing =
            throw IllegalStateException("raw-provider-secret should stay server-side")

        @GetMapping("/api/v1/test/data-integrity-error")
        fun dataIntegrity(): Nothing =
            throw DataIntegrityViolationException("Duplicate entry 'secret' for key 'secret_unique_key'")
    }
}
