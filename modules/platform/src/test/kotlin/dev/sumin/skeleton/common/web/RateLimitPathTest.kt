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
    fun `a path outside the pattern is not limited`() {
        val f = filter()
        repeat(3) { assertEquals(200, status(f, "/health")) }
    }
}
