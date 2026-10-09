package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountProperties.Email.RequiredWhen
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.InMemoryAccountRepository
import dev.sumin.skeleton.common.ApplicationException
import jakarta.servlet.DispatcherType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/** §9.2 — `required-when=WRITE`: 인터셉터를 서블릿 없이 직접. 메서드 × 경로 표, `PATCH /account/me`, `extra-exclude-paths` 의 메서드, 주소가 생기면 전부 통과, 읽는 법은 `SIGN_IN` 과 같다 */
class EmailOnWriteInterceptorTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val repo = InMemoryAccountRepository().also {
        it.insert(Account("acc_k", null, false, AccountStatus.ACTIVE, setOf("USER"), "K", null, null, now, now), listOf(Identity("idn_k", "acc_k", "kakao", "kakao-k", true, createdAt = now)))
        it.insert(Account("acc_m", "m@example.com", true, AccountStatus.ACTIVE, setOf("USER"), "M", null, null, now, now), listOf(Identity("idn_m", "acc_m", "password", "m@example.com", true, createdAt = now)))
    }

    private fun interceptor(extra: List<String> = emptyList(), `when`: RequiredWhen = RequiredWhen.WRITE, loginId: Boolean = false) =
        EmailRequiredInterceptor(AccountProperties.Email(required = true, requiredWhen = `when`, extraExcludePaths = extra), loginId) { repo }

    private fun request(method: String, uri: String, account: String? = "acc_k", context: String = "") = MockHttpServletRequest(method, uri).apply {
        requestURI = uri; contextPath = context
        if (account != null) userPrincipal = TestingAuthenticationToken(account, "n/a", "ROLE_USER")
    }

    /** 통과면 "pass", 막히면 에러 코드 */
    private fun verdict(i: EmailRequiredInterceptor, req: MockHttpServletRequest, handler: Any = Any()): String =
        try { if (i.preHandle(req, MockHttpServletResponse(), handler)) "pass" else "false" } catch (e: ApplicationException) { e.errorCode.code }

    private val blocked = "ACCOUNT.EMAIL_REQUIRED"

    @Test
    fun `method times path - GET HEAD and OPTIONS pass, every other method is blocked outside the allow list`() {
        val i = interceptor()
        for (path in listOf("/api/v1/things", "/api/v1/account/identities", "/api/v1/payments/orders", "/api/v1/fn")) {
            for (m in listOf("GET", "HEAD", "OPTIONS")) assertEquals("pass", verdict(i, request(m, path)), "$m $path reads")
            for (m in listOf("POST", "PUT", "PATCH", "DELETE", "TRACE", "PROPFIND", "get", "Get")) assertEquals(blocked, verdict(i, request(m, path)), "$m $path writes (and an unknown or oddly cased method counts as a write)")
        }
    }

    @Test
    fun `PATCH on me is open in this mode only - the other methods on me still follow the table`() {
        val i = interceptor()
        assertEquals("pass", verdict(i, request("PATCH", "/api/v1/account/me")))
        assertEquals("pass", verdict(i, request("PATCH", "/api/v1/account/%6De")), "an encoded spelling is the same path")
        assertEquals("pass", verdict(i, request("PATCH", "/api/v1/account/me;jsessionid=x")))
        for (m in listOf("POST", "PUT", "DELETE")) assertEquals(blocked, verdict(i, request(m, "/api/v1/account/me")), "$m me")
        assertEquals(blocked, verdict(i, request("PATCH", "/api/v1/account/me/extra")), "only that exact path")
        assertEquals(blocked, verdict(interceptor(`when` = RequiredWhen.SIGN_IN), request("PATCH", "/api/v1/account/me")), "the sign-in mode keeps blocking it")
    }

    @Test
    fun `the fixed allow list stays open for every method it names, also with the id gate on`() {
        val i = interceptor(loginId = true)
        for ((m, p) in listOf("GET" to "/api/v1/account/me", "POST" to "/api/v1/account/email/change", "POST" to "/api/v1/account/email/change/confirm", "POST" to "/api/v1/auth/logout", "POST" to "/api/v1/account/delete", "POST" to "/api/v1/account/delete/confirmation", "POST" to "/api/v1/account/discard", "POST" to "/api/v1/legal/consents", "POST" to "/api/v1/account/login-id", "POST" to "/api/v1/account/login-id/availability")) {
            assertEquals("pass", verdict(i, request(m, p)), "$m $p")
        }
        assertEquals(blocked, verdict(interceptor(), request("POST", "/api/v1/account/login-id")), "the id paths open only when the id feature is on")
    }

    @Test
    fun `payments are not opened by default - a write on them is blocked, a read passes`() {
        val i = interceptor()
        for (m in listOf("POST", "PUT", "PATCH", "DELETE")) assertEquals(blocked, verdict(i, request(m, "/api/v1/payments/orders")), "$m")
        assertEquals("pass", verdict(i, request("GET", "/api/v1/payments/orders")))
    }

    @Test
    fun `an extra exclude entry with a method opens that method only - a bare pattern opens all of them`() {
        val i = interceptor(extra = listOf("PATCH /api/v1/notifications/*/read", "POST /api/v1/storage/presign-download", "/api/v1/open/**"))
        assertEquals("pass", verdict(i, request("PATCH", "/api/v1/notifications/n1/read")))
        assertEquals(blocked, verdict(i, request("POST", "/api/v1/notifications/n1/read")))
        assertEquals(blocked, verdict(i, request("DELETE", "/api/v1/notifications/n1/read")))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/storage/presign-download")))
        assertEquals(blocked, verdict(i, request("PUT", "/api/v1/storage/presign-download")))
        assertEquals(blocked, verdict(i, request("POST", "/api/v1/storage/presign")), "upload stays closed")
        for (m in listOf("POST", "PUT", "PATCH", "DELETE")) assertEquals("pass", verdict(i, request(m, "/api/v1/open/x")), "$m")
    }

    @Test
    fun `an account with an address passes everything`() {
        val i = interceptor()
        for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "TRACE", "PROPFIND")) assertEquals("pass", verdict(i, request(m, "/api/v1/things", account = "acc_m")), m)
    }

    @Test
    fun `an unknown account id is left to the handler - nothing to check`() {
        assertEquals("pass", verdict(interceptor(), request("POST", "/api/v1/things", account = "acc_missing")))
    }

    @Test
    fun `no encoding trick gets a write past the guard - the path is read the way the router reads it`() {
        val i = interceptor()
        assertEquals(blocked, verdict(i, request("POST", "/%61pi/v1/things")))
        assertEquals(blocked, verdict(i, request("DELETE", "/api/v1/%74hings")))
        assertEquals(blocked, verdict(i, request("POST", "/app/%61pi/v1/things", context = "/app")), "the context path is not part of the pattern")
        assertEquals(blocked, verdict(i, request("PATCH", "/api/v1/account/%6De/../identities")), "dot segments do not turn into the allowed path")
        assertEquals("pass", verdict(i, request("GET", "/%61pi/v1/things")), "reads still pass")
    }

    @Test
    fun `any handler kind is guarded for a write - a functional route is not skipped`() {
        val route = org.springframework.web.servlet.function.HandlerFunction<org.springframework.web.servlet.function.ServerResponse> { throw IllegalStateException() }
        assertEquals(blocked, verdict(interceptor(), request("POST", "/api/v1/fn"), handler = route))
        assertEquals("pass", verdict(interceptor(), request("GET", "/api/v1/fn"), handler = route))
    }

    @Test
    fun `static resources, the error dispatch, a preflight, paths outside api and anonymous callers are left alone - as in the sign-in mode`() {
        val i = interceptor()
        assertEquals("pass", verdict(i, request("POST", "/api/v1/nothing-here"), handler = ResourceHttpRequestHandler()), "an unmapped path stays a 404, not a 403")
        assertEquals("pass", verdict(i, request("POST", "/api/v1/things").apply { dispatcherType = DispatcherType.ERROR }))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/things").apply { dispatcherType = DispatcherType.ASYNC }))
        val preflight = request("OPTIONS", "/api/v1/things").apply { addHeader("Origin", "http://localhost:3000"); addHeader("Access-Control-Request-Method", "POST") }
        assertEquals("pass", verdict(i, preflight))
        assertEquals("pass", verdict(i, request("POST", "/health")))
        assertEquals("pass", verdict(i, request("POST", "/api/v1/things", account = null)), "anonymous: the security chain says 401")
    }

    @Test
    fun `the sign-in mode is untouched - reads are blocked there`() {
        val i = interceptor(`when` = RequiredWhen.SIGN_IN)
        assertEquals(blocked, verdict(i, request("GET", "/api/v1/things")))
        assertEquals(blocked, verdict(i, request("HEAD", "/api/v1/things")))
        assertEquals(blocked, verdict(i, request("OPTIONS", "/api/v1/things")), "a non-preflight OPTIONS is guarded in this mode")
    }
}
