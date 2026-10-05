package dev.sumin.skeleton.common.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

class CorsDefaultsTest {
    /** 프론트(react-skeleton api-client)가 모든 요청에 붙이는 헤더 — 기본값이 막으면 켜자마자 cross-origin preflight 가 실패한다 */
    @Test
    fun `default CORS allows the headers the skeleton frontend sends on every request`() {
        val source = WebPolicyAutoConfiguration().corsConfigurationSource(WebProperties(cors = WebProperties.Cors(enabled = true)))
        val request = MockHttpServletRequest("OPTIONS", "/api/v1/notes").apply { requestURI = "/api/v1/notes" }
        val configuration = assertNotNull((source as UrlBasedCorsConfigurationSource).getCorsConfiguration(request))

        val allowed = configuration.checkHeaders(listOf("X-Time-Zone", "traceparent", "Idempotency-Key", "Authorization"))

        assertEquals(listOf("X-Time-Zone", "traceparent", "Idempotency-Key", "Authorization"), allowed)
    }
}
