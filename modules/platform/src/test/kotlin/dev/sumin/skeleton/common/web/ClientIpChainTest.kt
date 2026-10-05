package dev.sumin.skeleton.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.filter.ForwardedHeaderFilter
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 플랫폼 기본 필터 순서(ClientIpFilter → ForwardedHeaderFilter)로 실제 요청을 흘려, 한도 키가 무엇이 되는지 본다.
 * ForwardedHeaderFilter 는 `X-Forwarded-For` 로 `remoteAddr` 를 **덮어쓴다** — 직접 붙은 클라이언트가 한도 키를 고를 수 있다.
 */
class ClientIpChainTest {
    private fun keyThrough(ips: ClientIps, withClientIpFilter: Boolean, build: MockHttpServletRequest.() -> Unit): String {
        val resolver = ClientIpRateLimitKeyResolver(ips)
        var key = ""
        val sink = FilterChain { req, _ -> key = resolver.resolve(req as HttpServletRequest) }
        val forwarded = ForwardedHeaderFilter()
        val request = MockHttpServletRequest().apply(build)
        val response = MockHttpServletResponse()
        if (withClientIpFilter) {
            ClientIpFilter(ips).doFilter(request, response) { req, res -> forwarded.doFilter(req, res, sink) }
        } else {
            forwarded.doFilter(request, response, sink)
        }
        return key
    }

    @Test
    fun `unset mode keeps today's behaviour - a direct caller can pick its rate limit key with X-Forwarded-For`() {
        val key = keyThrough(ClientIps(WebProperties.ClientIp()), withClientIpFilter = false) {
            remoteAddr = "203.0.113.5"; addHeader("X-Forwarded-For", "6.6.6.6")
        }
        assertEquals("6.6.6.6", key) // 문서화된 기존 동작 — mode 를 정하면 막힌다
    }

    @Test
    fun `direct mode keys by the real peer even though ForwardedHeaderFilter rewrote remoteAddr`() {
        val key = keyThrough(ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT)), withClientIpFilter = true) {
            remoteAddr = "203.0.113.5"; addHeader("X-Forwarded-For", "6.6.6.6"); addHeader("Forwarded", "for=7.7.7.7")
        }
        assertEquals("203.0.113.5", key)
    }

    @Test
    fun `proxy mode keys by the client the trusted proxy appended`() {
        val key = keyThrough(ClientIps(WebProperties.ClientIp(mode = ClientIpMode.PROXY)), withClientIpFilter = true) {
            remoteAddr = "127.0.0.1"; addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.20")
        }
        assertEquals("198.51.100.20", key)
    }

    @Test
    fun `the resolved client is computed once, before any filter rewrites the request`() {
        var seenByFilter = ""
        val ips = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT))
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.5"; addHeader("X-Forwarded-For", "6.6.6.6") }
        ClientIpFilter(ips).doFilter(request, MockHttpServletResponse()) { req, _ -> seenByFilter = (req as HttpServletRequest).getAttribute(ClientIps.ATTRIBUTE).toString() }
        assertEquals(ClientAddress("203.0.113.5", "203.0.113.5").toString(), seenByFilter)
    }
}
