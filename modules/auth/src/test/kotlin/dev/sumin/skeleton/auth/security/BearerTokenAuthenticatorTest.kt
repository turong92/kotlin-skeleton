package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.ObjectMapper

/** `JwtAuthenticationFilter` 가 Bearer 를 JWT 로 읽기 전에 [BearerTokenAuthenticator] 에게 먼저 묻는다 - 고리가 비어 있으면 예전과 같다 */
class BearerTokenAuthenticatorTest {
    private val jwt = JwtTokenService(AuthProperties.Jwt(secret = "a-test-secret-that-is-at-least-32-bytes-long"))
    private val errors = AuthErrorWriter(ObjectMapper())

    private enum class TestCode(override val code: String, override val status: HttpStatus, override val title: String) : ErrorCode {
        GONE("TEST.GONE", HttpStatus.UNAUTHORIZED, "Gone"),
        BLOCKED("TEST.BLOCKED", HttpStatus.FORBIDDEN, "Blocked"),
    }

    private class Prefixed(private val handle: (String) -> Authentication) : BearerTokenAuthenticator {
        val seen = ArrayList<String>()
        override fun supports(token: String) = token.startsWith("tk_")
        override fun authenticate(token: String, request: HttpServletRequest): Authentication = handle(token).also { seen += token }
    }

    private class Call(val response: MockHttpServletResponse, val seenInChain: Authentication?)

    private fun call(filter: JwtAuthenticationFilter, bearer: String): Call {
        SecurityContextHolder.clearContext()
        val request = MockHttpServletRequest("GET", "/api/v1/notes").apply { addHeader("Authorization", "Bearer $bearer") }
        val response = MockHttpServletResponse()
        var seen: Authentication? = null
        val capturing = object : MockFilterChain() {
            override fun doFilter(request: jakarta.servlet.ServletRequest, response: jakarta.servlet.ServletResponse) {
                seen = SecurityContextHolder.getContext().authentication
                super.doFilter(request, response)
            }
        }
        filter.doFilter(request, response, capturing)
        SecurityContextHolder.clearContext()
        return Call(response, seen)
    }

    private fun filter(vararg authenticators: BearerTokenAuthenticator) = JwtAuthenticationFilter(jwt, errors, emptyList(), authenticators.toList())

    @Test
    fun `a token an authenticator supports never reaches JWT parsing and its authentication runs the request`() {
        val key = Prefixed { TestingAuthenticationToken("acc_1", null).also { it.isAuthenticated = true } }

        val result = call(filter(key), "tk_abc")

        assertEquals(200, result.response.status)
        assertEquals("acc_1", result.seenInChain?.name)
        assertEquals(listOf("tk_abc"), key.seen)
    }

    @Test
    fun `a rejection is written with the authenticator's own code and status and stops the chain`() {
        val key = Prefixed { throw BearerTokenRejectedException(TestCode.BLOCKED, data = mapOf("why" to "owner")) }

        val blocked = call(filter(key), "tk_abc")

        assertEquals(403, blocked.response.status)
        assertTrue(blocked.response.contentAsString.contains("\"code\":\"TEST.BLOCKED\""))
        assertTrue(blocked.response.contentAsString.contains("\"why\":\"owner\""))
        assertNull(blocked.seenInChain)

        val gone = call(filter(Prefixed { throw BearerTokenRejectedException(TestCode.GONE) }), "tk_abc")
        assertEquals(401, gone.response.status)
        assertTrue(gone.response.contentAsString.contains("\"code\":\"TEST.GONE\""))
    }

    @Test
    fun `without a matching authenticator the token is a JWT as before - same answer with or without the hook`() {
        val none = call(filter(), "eyJ.not.valid")
        val unrelated = call(filter(Prefixed { error("must not be asked") }), "eyJ.not.valid")

        assertEquals(401, none.response.status)
        assertEquals(none.response.status, unrelated.response.status)
        assertEquals(none.response.contentAsString.replace(Regex("\"timestamp\":\"[^\"]*\""), ""), unrelated.response.contentAsString.replace(Regex("\"timestamp\":\"[^\"]*\""), ""))

        val token = jwt.issue(CurrentPrincipal("acc_9", roles = setOf("USER"))).accessToken
        val ok = call(filter(Prefixed { error("must not be asked") }), token)
        assertEquals("acc_9", ok.seenInChain?.name)
    }
}
