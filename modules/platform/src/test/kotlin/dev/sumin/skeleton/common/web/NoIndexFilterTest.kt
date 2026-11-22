package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.deploy.DeployEnv
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class NoIndexFilterTest {
    @Test
    fun `the filter marks every response noindex — error responses too`() {
        val ok = MockHttpServletResponse()
        NoIndexFilter().doFilter(MockHttpServletRequest("GET", "/api/ping"), ok, MockFilterChain())
        assertThat(ok.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow")

        val error = MockHttpServletResponse()
        val failing = FilterChain { _, res -> (res as HttpServletResponse).sendError(500) }
        NoIndexFilter().doFilter(MockHttpServletRequest("GET", "/x"), error, failing)
        assertThat(error.getHeader("X-Robots-Tag")).isEqualTo("noindex, nofollow")
    }

    @Test
    fun `only local and stage switch it on — unset and prod leave responses alone`() {
        fun enabled(env: String?) = WebPolicyAutoConfiguration()
            .noIndexFilterRegistration(MockEnvironment().apply { env?.let { setProperty("skeleton.env", it) } })
            .isEnabled

        assertThat(enabled("local")).isTrue()
        assertThat(enabled("stage")).isTrue()
        assertThat(enabled("prod")).isFalse()
        assertThat(enabled(null)).isFalse()
        assertThat(NoIndexFilter.enabledFor(DeployEnv.PROD)).isFalse()
    }
}
