package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.common.project.ProjectScoped
import dev.sumin.skeleton.common.web.BearerIgnoredPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.ObjectMapper

/** 깨우기 경로의 Bearer(Scheduler OIDC 의 Google 토큰)는 읽지 않고, 프로젝트 없는 경로의 Bearer 는 500 이 아니라 401 이다. */
class JwtAuthenticationFilterBearerIgnoredTest {
    private val single = JwtTokenService(AuthProperties.Jwt(secret = "a-test-secret-that-is-at-least-32-bytes-long"))
    private val perProject = JwtTokenService.perProject(ProjectScoped { AuthProperties.Jwt(issuer = "iss-${it.id}", secret = "secret-of-${it.id}-padded-to-32-bytes!!") })
    private val errors = AuthErrorWriter(ObjectMapper())

    private fun call(filter: JwtAuthenticationFilter, uri: String, bearer: String): Pair<MockHttpServletResponse, MockFilterChain> {
        val request = MockHttpServletRequest("POST", uri).apply { addHeader("Authorization", "Bearer $bearer") }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    @Test
    fun `a foreign Bearer on an ignored path passes through - the path does its own authentication`() {
        val filter = JwtAuthenticationFilter(single, errors, listOf(BearerIgnoredPaths { listOf("/internal/jobs/drain") }))

        val (response, chain) = call(filter, "/internal/jobs/drain", "google.oidc.token")

        assertEquals(200, response.status)
        assertTrue(chain.request != null, "the chain must continue")
        // 다른 경로에서는 같은 헤더가 그대로 401
        assertEquals(401, call(filter, "/api/v1/auth/me", "google.oidc.token").first.status)
    }

    @Test
    fun `without a project context a Bearer is an authentication failure - 401, never an exception`() {
        val filter = JwtAuthenticationFilter(perProject, errors)

        val (response, _) = call(filter, "/internal/jobs/drain", "any.bearer.value")

        assertEquals(401, response.status)
    }
}
