package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.GatePathRule
import jakarta.servlet.DispatcherType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/**
 * 두 게이트(이메일 · 아이디)가 나누는 「이 요청을 검사할 것인가」 — 경로를 스프링이 핸들러를 고르는 방식으로 읽는지, 핸들러 종류와 무관한지,
 * 예외(정적 자원 · 에러 디스패치 · preflight · 익명)만 빠지는지. 두 인터셉터 시험이 같은 단정을 되풀이하지 않도록 여기 한 벌만 둔다.
 */
class GateRequestsTest {
    private val open = listOf("GET /api/v1/account/me", "POST /api/v1/account/login-id").map { GatePathRule.parse(it) }

    private fun request(method: String, uri: String, context: String = "") = MockHttpServletRequest(method, uri).apply {
        requestURI = uri; contextPath = context
        userPrincipal = TestingAuthenticationToken("acc_1", "n/a", "ROLE_USER")
    }

    private fun check(req: MockHttpServletRequest, handler: Any = Any()): String? = GateRequests.callerToCheck(req, handler, open)

    @Test
    fun `a percent-encoded path is the same path - the router decodes it, so does the guard`() {
        assertEquals("acc_1", check(request("GET", "/%61pi/v1/things")), "/%61pi is /api")
        assertEquals("acc_1", check(request("GET", "/api/v1/%74hings")))
        assertEquals("acc_1", check(request("GET", "/app/%61pi/v1/things", context = "/app")), "the context path is not part of the pattern")
        assertEquals("acc_1", check(request("GET", "/api//v1/things")), "a double slash does not walk out of the protected prefix")
        assertEquals("acc_1", check(request("GET", "/api/v1/things;a=b")), "a matrix parameter is not part of the path")
    }

    @Test
    fun `any handler kind under the protected prefix is guarded - a functional route is not skipped`() {
        val functional = HandlerFunction<ServerResponse> { throw IllegalStateException() }
        assertEquals("acc_1", check(request("GET", "/api/v1/fn"), handler = functional))
    }

    @Test
    fun `HEAD follows the GET rules in both directions`() {
        assertEquals("acc_1", check(request("HEAD", "/api/v1/things")))
        assertNull(check(request("HEAD", "/api/v1/account/me")), "GET me is open, so HEAD me is too")
        assertEquals("acc_1", check(request("HEAD", "/api/v1/account/login-id")), "POST login-id does not open HEAD")
    }

    @Test
    fun `static resources, the error dispatch, async completion and a CORS preflight are not guarded`() {
        assertNull(check(request("GET", "/api/v1/nothing-here"), handler = ResourceHttpRequestHandler()), "an unmapped path stays a 404, not a 403")
        assertNull(check(request("GET", "/api/v1/things").apply { dispatcherType = DispatcherType.ERROR }), "the error page of a failed request is not blocked again")
        assertNull(check(request("GET", "/api/v1/things").apply { dispatcherType = DispatcherType.ASYNC }))
        val preflight = request("OPTIONS", "/api/v1/things").apply { addHeader("Origin", "http://localhost:3000"); addHeader("Access-Control-Request-Method", "POST") }
        assertNull(check(preflight))
        assertEquals("acc_1", check(request("OPTIONS", "/api/v1/things")), "an OPTIONS call that is not a preflight is guarded like any other")
    }

    @Test
    fun `paths outside the api prefix and anonymous callers are left alone`() {
        assertNull(check(request("GET", "/health")))
        assertNull(check(MockHttpServletRequest("GET", "/api/v1/things").apply { requestURI = "/api/v1/things" }), "no principal: the security chain says 401")
        val anonymous = MockHttpServletRequest("GET", "/api/v1/things").apply {
            requestURI = "/api/v1/things"
            userPrincipal = AnonymousAuthenticationToken("k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        }
        assertNull(check(anonymous))
    }
}
