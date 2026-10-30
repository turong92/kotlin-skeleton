package dev.sumin.skeleton.common.web

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** 속도 제한 필터는 서블릿 필터라 디스패처가 경로를 풀기 전에 돈다 — 그래도 **스프링이 핸들러를 고르는 것과 같은 경로**(디코드 · 세그먼트 단위)로 맞춰야 한다 */
class RateLimitPathTest {
    private fun filter(pattern: String = "/api/**") = RateLimitFilter(
        WebProperties.RateLimit(enabled = true, pathPattern = pattern, capacity = 1),
        { "k" }, InMemoryFixedWindowRateLimitStore(), tools.jackson.databind.ObjectMapper(),
    )

    private fun status(f: RateLimitFilter, uri: String, contextPath: String = ""): Int {
        val req = MockHttpServletRequest("POST", uri).apply { requestURI = uri; this.contextPath = contextPath }
        val res = MockHttpServletResponse()
        f.doFilter(req, res, MockFilterChain())
        return res.status
    }

    @Test
    fun `a percent-encoded prefix does not slip past the limit - the second call is 429 whatever spelling the path has`() {
        val f = filter()
        assertEquals(200, status(f, "/api/v1/auth/login"))
        assertEquals(429, status(f, "/%61pi/v1/auth/login"), "/%61pi is /api for the router")
    }

    @Test
    fun `a path parameter segment and a context path are read the way the router reads them`() {
        val f = filter()
        assertEquals(200, status(f, "/app/api;x=1/v1/y", contextPath = "/app"))
        assertEquals(429, status(f, "/app/api/v1/y", contextPath = "/app"))
    }

    @Test
    fun `a list of patterns limits each listed path, an unlisted path stays free, and the single key keeps working when the list is empty`() {
        val listed = RateLimitFilter(
            WebProperties.RateLimit(enabled = true, pathPatterns = listOf("/api/v1/badges/accounts/**", "/api/v1/reviews/targets/**"), capacity = 1),
            { "k" }, InMemoryFixedWindowRateLimitStore(), tools.jackson.databind.ObjectMapper(),
        )
        assertEquals(200, status(listed, "/api/v1/badges/accounts/acc_1"))
        assertEquals(429, status(listed, "/api/v1/reviews/targets/product/basic/reviews"), "the listed paths share one counter per client")
        repeat(3) { assertEquals(200, status(listed, "/api/v1/notes")) }
        // 목록이 비어 있으면 지금까지처럼 단일 pathPattern 이 그대로 먹는다(하위 호환)
        assertEquals(listOf("/api/v1/x/**"), WebProperties.RateLimit(pathPattern = "/api/v1/x/**").effectivePatterns())
        assertEquals(listOf("/api/**"), WebProperties.RateLimit().effectivePatterns())
        val single = filter("/api/v1/badges/accounts/**")
        assertEquals(200, status(single, "/api/v1/badges/accounts/a"))
        assertEquals(429, status(single, "/api/v1/badges/accounts/b"))
        repeat(3) { assertEquals(200, status(single, "/api/v1/reviews/targets/product/basic/reviews")) }
    }

    @Test
    fun `a path outside the pattern is not limited`() {
        val f = filter()
        repeat(3) { assertEquals(200, status(f, "/health")) }
    }
}
