package dev.sumin.skeleton.api

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
import kotlin.test.assertEquals

/**
 * `/api/v1/hello` 통합 테스트.
 *
 * - Testcontainers 가 실제 MySQL 컨테이너 띄움 (Flyway 마이그레이션 포함 검증)
 * - MockMvc 로 HTTP 레이어까지 왕복
 * - traceId 전파 검증: X-Request-Id 헤더 보내면 X-Trace-Id 로 승계되는지
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
        mockMvc.get("/api/v1/hello") {
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.message") { value("Hello from Kotlin backend!") }
            jsonPath("$.timestamp") { isNotEmpty() }
            header { exists(TraceIdFilter.HEADER_TRACE_ID) }
        }
    }

    @Test
    fun `GET hello propagates X-Request-Id as traceId`() {
        val clientRequestId = "integration-test-abc-123"
        mockMvc.get("/api/v1/hello") {
            header(TraceIdFilter.HEADER_REQUEST_ID, clientRequestId)
        }.andExpect {
            status { isOk() }
            header {
                string(TraceIdFilter.HEADER_TRACE_ID, clientRequestId)
            }
        }
    }

    @Test
    fun `unmapped path returns standardized ApiError with traceId`() {
        val result = mockMvc.get("/api/v1/does-not-exist") {
            header(TraceIdFilter.HEADER_REQUEST_ID, "err-trace-xyz")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.status") { value(404) }
            jsonPath("$.title") { isNotEmpty() }
            jsonPath("$.traceId") { value("err-trace-xyz") }
            jsonPath("$.timestamp") { isNotEmpty() }
        }.andReturn()

        // 응답 헤더에도 traceId
        assertEquals("err-trace-xyz", result.response.getHeader(TraceIdFilter.HEADER_TRACE_ID))
    }
}
