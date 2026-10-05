package dev.sumin.skeleton.idempotency

import dev.sumin.skeleton.common.web.ClientIpMode
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.WebProperties
import java.security.Principal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class PrincipalIdempotencyScopeResolverTest {
    @Test
    fun `an authenticated caller is scoped by principal name`() {
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.10"; userPrincipal = Principal { "alice" } }
        assertThat(PrincipalIdempotencyScopeResolver().resolve(request)).isEqualTo("alice")
    }

    @Test
    fun `an anonymous caller is scoped by the client IP ClientIps resolves, so one caller cannot pick another's scope with a header`() {
        val resolver = PrincipalIdempotencyScopeResolver(ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT)))
        val request = MockHttpServletRequest().apply { remoteAddr = "203.0.113.10" }

        assertThat(resolver.resolve(request)).isEqualTo("anonymous:203.0.113.10")
    }

    @Test
    fun `behind a trusted proxy the anonymous scope is the forwarded client, not the proxy`() {
        val resolver = PrincipalIdempotencyScopeResolver(ClientIps(WebProperties.ClientIp(mode = ClientIpMode.PROXY)))
        val request = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1"; addHeader("X-Forwarded-For", "198.51.100.20") }

        assertThat(resolver.resolve(request)).isEqualTo("anonymous:198.51.100.20")
    }
}
