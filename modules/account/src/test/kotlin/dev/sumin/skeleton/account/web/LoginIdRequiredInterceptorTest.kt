package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken

/** B6 — 아이디 게이트를 서블릿 없이 직접: 허용 목록의 경로마다 통과 · 밖은 읽기도 403 · 인코딩 우회 없음 · 익명 · preflight · HEAD · 계정 한 줄을 뒤의 게이트와 나눠 읽기 */
class LoginIdRequiredInterceptorTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val repo = InMemoryAccountRepository().also {
        // 아이디가 없는 소셜 계정 (이메일도 없다) · 아이디는 있는데 이메일이 없는 계정 · 둘 다 있는 계정 · 아이디만 없는 이메일 계정
        it.insert(Account("acc_none", null, false, AccountStatus.ACTIVE, setOf("USER"), "N", null, null, now, now), listOf(Identity("idn_n", "acc_none", "kakao", "k-n", true, createdAt = now)))
        it.insert(Account("acc_id", null, false, AccountStatus.ACTIVE, setOf("USER"), "I", null, null, now, now, loginId = "has.id"), listOf(Identity("idn_i", "acc_id", "kakao", "k-i", true, createdAt = now)))
        it.insert(Account("acc_mail", "m@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "M", null, null, now, now), listOf(Identity("idn_m", "acc_mail", "password", "m@example.com", true, createdAt = now)))
    }
    private fun interceptor(extra: List<String> = emptyList(), accounts: () -> AccountRepository = { repo }) =
        LoginIdRequiredInterceptor(AccountProperties.LoginId(enabled = true, extraExcludePaths = extra), accounts)

    private fun request(method: String, uri: String, account: String = "acc_none", context: String = "") = MockHttpServletRequest(method, uri).apply {
        requestURI = uri; contextPath = context
        userPrincipal = TestingAuthenticationToken(account, "n/a", "ROLE_USER")
    }

    /** 통과면 "pass", 막히면 에러 코드 */
    private fun verdict(i: LoginIdRequiredInterceptor, req: MockHttpServletRequest, handler: Any = Any()): String =
        try { if (i.preHandle(req, MockHttpServletResponse(), handler)) "pass" else "false" } catch (e: ApplicationException) { e.errorCode.code }

    private val blocked = "ACCOUNT.LOGIN_ID_REQUIRED"

    @Test
    fun `every path of the allow list passes for an account without an id - the email gate's fixed list plus the two login id paths`() {
        val i = interceptor()
        val open = listOf(
            "GET" to "/api/v1/account/me", "HEAD" to "/api/v1/account/me",
            "POST" to "/api/v1/account/login-id", "POST" to "/api/v1/account/login-id/availability",
            "POST" to "/api/v1/account/email/change", "POST" to "/api/v1/account/email/change/confirm",
            "POST" to "/api/v1/account/discard", "POST" to "/api/v1/account/delete", "POST" to "/api/v1/account/delete/confirmation",
            "POST" to "/api/v1/auth/logout", "GET" to "/api/v1/auth/sessions", "GET" to "/api/v1/legal/documents", "POST" to "/api/v1/legal/consents", "GET" to "/api/v1/docs/openapi",
        )
        open.forEach { (m, p) -> assertEquals("pass", verdict(i, request(m, p)), "$m $p must stay open for an account without an id") }
    }

    @Test
    fun `outside the allow list even a read is stopped, on any method`() {
        val i = interceptor()
        listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE").forEach { assertEquals(blocked, verdict(i, request(it, "/api/v1/things")), it) }
        assertEquals(blocked, verdict(i, request("GET", "/api/v1/account/identities")))
        assertEquals(blocked, verdict(i, request("PATCH", "/api/v1/account/me")), "only GET me is open")
        assertEquals(blocked, verdict(i, request("GET", "/api/v1/account/login-id")), "the claim path is POST only")
        assertEquals(blocked, verdict(i, request("PUT", "/api/v1/account/login-id")))
        assertEquals(blocked, verdict(i, request("GET", "/api/v1/account/login-id/availability")))
        assertEquals(blocked, verdict(i, request("POST", "/api/v1/account/login-id/forgot")), "the reminder is public - it is not a signed-in call and not on the allow list")
    }

    @Test
    fun `an account with an id passes everywhere - with or without an address`() {
        val i = interceptor()
        assertEquals("pass", verdict(i, request("GET", "/api/v1/things", account = "acc_id")))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/things", account = "acc_id")))
    }

    @Test
    fun `the gate is about the id only - an account with an address but no id is stopped`() {
        assertEquals(blocked, verdict(interceptor(), request("GET", "/api/v1/things", account = "acc_mail")))
    }

    @Test
    fun `the allow list is read the same way - an encoded or parameterised spelling of an allowed path is allowed, not another path`() {
        val i = interceptor()
        assertEquals("pass", verdict(i, request("GET", "/api/v1/account/%6De")))
        assertEquals("pass", verdict(i, request("GET", "/api/v1/account/me;jsessionid=x")))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/account/login-i%64")))
        assertEquals(blocked, verdict(i, request("POST", "/api/v1/account/login-id/%2e%2e/things")), "an encoded dot-dot is not a way out of the list")
        assertEquals(blocked, verdict(i, request("POST", "/api/v1/account/login-id-other")))
    }

    @Test
    fun `a caller whose account row is gone is not this gate's business`() {
        assertEquals("pass", verdict(interceptor(), request("GET", "/api/v1/things", account = "acc_ghost")))
    }

    @Test
    fun `extra exclude paths only add - and the built-in ones cannot be removed by configuration`() {
        val i = interceptor(extra = listOf("POST /api/v1/extra/**", "/api/v1/open/**"))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/extra/x")))
        assertEquals(blocked, verdict(i, request("GET", "/api/v1/extra/x")))
        assertEquals("pass", verdict(i, request("DELETE", "/api/v1/open/x")))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/account/login-id")))
        assertEquals("pass", verdict(i, request("GET", "/api/v1/account/me")))
    }

    @Test
    fun `the email gate's extra paths open only the email gate - an id-less account is still stopped by this one`() {
        val emailGate = EmailRequiredInterceptor(AccountProperties.Email(required = true, extraExcludePaths = listOf("GET /api/v1/mail-only/**")), loginIdEnabled = true) { repo }
        val req = request("GET", "/api/v1/mail-only/x")
        assertEquals("pass", try { emailGate.preHandle(req, MockHttpServletResponse(), Any()).let { "pass" } } catch (e: ApplicationException) { e.errorCode.code })
        assertEquals(blocked, verdict(interceptor(), request("GET", "/api/v1/mail-only/x")))
    }

    @Test
    fun `the email gate shares the allow list - with login id on it opens the two login id paths, off it does not`() {
        val on = EmailRequiredInterceptor(AccountProperties.Email(required = true), loginIdEnabled = true) { repo }
        val off = EmailRequiredInterceptor(AccountProperties.Email(required = true)) { repo }
        fun v(i: EmailRequiredInterceptor, m: String, p: String) = try { i.preHandle(request(m, p), MockHttpServletResponse(), Any()); "pass" } catch (e: ApplicationException) { e.errorCode.code }
        assertEquals("pass", v(on, "POST", "/api/v1/account/login-id"))
        assertEquals("pass", v(on, "POST", "/api/v1/account/login-id/availability"))
        assertEquals("ACCOUNT.EMAIL_REQUIRED", v(on, "GET", "/api/v1/account/login-id"))
        assertEquals("ACCOUNT.EMAIL_REQUIRED", v(off, "POST", "/api/v1/account/login-id"), "login id off: the list is today's list")
        assertEquals("pass", v(off, "GET", "/api/v1/account/me"))
    }

    @Test
    fun `the account row is read once per request - the id gate leaves it for the email gate`() {
        var reads = 0
        val counting = object : AccountRepository by repo {
            override fun findById(id: String): Account? { reads++; return repo.findById(id) }
        }
        val idGate = LoginIdRequiredInterceptor(AccountProperties.LoginId(enabled = true), { counting })
        val emailGate = EmailRequiredInterceptor(AccountProperties.Email(required = true), loginIdEnabled = true) { counting }
        // an account that has an id but no address: the id gate lets it through and hands the row over, the email gate stops it without reading again
        val req = request("GET", "/api/v1/things", account = "acc_id")
        assertEquals("pass", verdict(idGate, req))
        assertNotNull(req.getAttribute(GateAccount.ATTRIBUTE), "the row is left on the request")
        assertFailsWith<ApplicationException> { emailGate.preHandle(req, MockHttpServletResponse(), Any()) }.also { assertEquals("ACCOUNT.EMAIL_REQUIRED", it.errorCode.code) }
        assertEquals(1, reads, "one read for both gates")
        // an allowed path is not read at all by the id gate - the email gate does its own read
        reads = 0
        val open = request("GET", "/api/v1/account/me", account = "acc_id")
        assertEquals("pass", verdict(idGate, open)); assertEquals(0, reads)
        assertTrue(open.getAttribute(GateAccount.ATTRIBUTE) == null)
    }
}
