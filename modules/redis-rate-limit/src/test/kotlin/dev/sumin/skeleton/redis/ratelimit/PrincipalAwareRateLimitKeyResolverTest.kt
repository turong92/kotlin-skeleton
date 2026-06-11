package dev.sumin.skeleton.redis.ratelimit

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
}
