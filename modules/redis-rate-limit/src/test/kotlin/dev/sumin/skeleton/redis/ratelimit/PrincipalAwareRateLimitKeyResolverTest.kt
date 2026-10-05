package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.common.web.ClientIpMode
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.WebProperties
import java.security.Principal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class PrincipalAwareRateLimitKeyResolverTest {
    private val resolver = PrincipalAwareRateLimitKeyResolver()

    @Test
    fun `uses authenticated principal before remote ip`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            userPrincipal = Principal { "alice" }
        }

        assertThat(resolver.resolve(request)).isEqualTo("principal:alice")
    }

    @Test
    fun `falls back to remote ip for anonymous request`() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
        }

        assertThat(resolver.resolve(request)).isEqualTo("ip:203.0.113.10")
    }

    @Test
    fun `anonymous callers are keyed by the client IP the app's ClientIps resolves, not the raw peer`() {
        val behindProxy = PrincipalAwareRateLimitKeyResolver(ClientIps(WebProperties.ClientIp(mode = ClientIpMode.PROXY)))
        val request = MockHttpServletRequest().apply {
            remoteAddr = "127.0.0.1"
            addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.20")
        }

        assertThat(behindProxy.resolve(request)).isEqualTo("ip:198.51.100.20")
    }

    @Test
    fun `an IPv6 caller is keyed by its 64-bit prefix once a mode is set`() {
        val direct = PrincipalAwareRateLimitKeyResolver(ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT)))
        val request = MockHttpServletRequest().apply { remoteAddr = "2001:db8:1:2:aaaa::1" }

        assertThat(direct.resolve(request)).isEqualTo("ip:2001:db8:1:2:0:0:0:0/64")
    }
}
