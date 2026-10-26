package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken

/** 인터셉터를 서블릿 없이 직접 — 경로를 **스프링이 핸들러를 고르는 방식**으로 읽는지, 핸들러 종류와 무관한지, 예외(정적 자원 · 에러 디스패치 · preflight)만 빠지는지 */
class EmailRequiredInterceptorTest {
    private val repo = InMemoryAccountRepository().also {
        val now = Instant.parse("2026-10-08T00:00:00Z")
        it.insert(Account("acc_k", null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now), listOf(Identity("idn_k", "acc_k", "kakao", "kakao-k", true, createdAt = now)))
    }
    private fun interceptor(extra: List<String> = emptyList()) = EmailRequiredInterceptor(AccountProperties.Email(required = true, extraExcludePaths = extra)) { repo }

    private fun request(method: String, uri: String, context: String = "") = MockHttpServletRequest(method, uri).apply {
        requestURI = uri; contextPath = context
        userPrincipal = TestingAuthenticationToken("acc_k", "n/a", "ROLE_USER")
    }

    /** 통과면 true, 막히면 에러 코드 */
    private fun verdict(i: EmailRequiredInterceptor, req: MockHttpServletRequest, handler: Any = Any()): String =
        try { if (i.preHandle(req, MockHttpServletResponse(), handler)) "pass" else "false" } catch (e: ApplicationException) { e.errorCode.code }

    @Test
    fun `the allow list is read the same way - an encoded or parameterised spelling of an allowed path is still allowed`() {
        val i = interceptor()
        assertEquals("pass", verdict(i, request("GET", "/api/v1/account/%6De")))
        assertEquals("pass", verdict(i, request("GET", "/api/v1/account/me;jsessionid=x")))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/auth/logout")))
        assertEquals("ACCOUNT.EMAIL_REQUIRED", verdict(i, request("POST", "/api/v1/account/me")), "the method counts: only GET me is open")
    }

    @Test
    fun `extra exclude paths only add - the built-in ones cannot be removed by configuration`() {
        val i = interceptor(extra = listOf("POST /api/v1/extra/**", "/api/v1/open/**"))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/extra/x")))
        assertEquals("ACCOUNT.EMAIL_REQUIRED", verdict(i, request("GET", "/api/v1/extra/x")))
        assertEquals("pass", verdict(i, request("DELETE", "/api/v1/open/x")))
        for ((m, p) in listOf("GET" to "/api/v1/account/me", "POST" to "/api/v1/account/email/change", "POST" to "/api/v1/account/email/change/confirm", "POST" to "/api/v1/auth/logout", "POST" to "/api/v1/account/delete", "POST" to "/api/v1/account/delete/confirmation", "POST" to "/api/v1/account/discard")) {
            assertEquals("pass", verdict(i, request(m, p)), "$m $p must stay open for an account without an address")
        }
    }

    @Test
    fun `a rule that cannot be read fails at startup naming the setting - it is never silently ignored`() {
        for (bad in listOf("GET api/v1/x", "FETCH /api/v1/x", "", "POST", "/api/v1/{x", "GET  /api/v1/x extra")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { AccountProperties.Email(extraExcludePaths = listOf(bad)) }
            assertTrue("skeleton.account.email.extra-exclude-paths" in e.message.orEmpty(), e.message)
        }
    }
}
