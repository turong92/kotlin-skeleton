package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.login.LoginFailure
import dev.sumin.skeleton.authtest.PasswordLoginWebTestApplication
import dev.sumin.skeleton.authtest.RecordingLoginHooks
import dev.sumin.skeleton.authtest.RecordingLoginHooksBeans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** `POST /api/v1/auth/login` 의 와이어 — 경로 · 요청 모양 · 에러 코드. 시드 계정(`user@example.com` / `password`)은 `auth` 의 기본 저장소 */
@SpringBootTest(classes = [PasswordLoginWebTestApplication::class], properties = ["skeleton.web.client-ip.mode=direct"])
@AutoConfigureMockMvc
@Import(RecordingLoginHooksBeans::class)
class PasswordLoginWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var hooks: RecordingLoginHooks

    @BeforeEach fun clear() { hooks.attempts.clear(); hooks.failures.clear(); hooks.successes.clear() }

    private fun login(body: String, ip: String = "203.0.113.5") =
        mvc.perform(post("/api/v1/auth/login").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(body))

    @Test
    fun `a right password answers 200 with the token and the principal, without any bearer header`() {
        login("""{"email":"user@example.com","password":"password"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value.accessToken").isString)
            .andExpect(jsonPath("$.value.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.value.principal.accountId").value("acc_user"))
        assertEquals(listOf("acc_user"), hooks.successes)
    }

    @Test
    fun `a wrong password and an unknown account are the same 401 AUTH_INVALID_CREDENTIALS, and the hooks hear of both`() {
        login("""{"email":"user@example.com","password":"nope-nope-1"}""").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"))
        login("""{"email":"ghost@example.com","password":"nope-nope-1"}""").andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("AUTH.INVALID_CREDENTIALS"))
        assertEquals(listOf(LoginFailure.BAD_PASSWORD, LoginFailure.UNKNOWN_ACCOUNT), hooks.failures)
    }

    @Test
    fun `a request with no identifier is a 400 naming the RequiredLoginIdentifier rule`() {
        login("""{"password":"password"}""")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
            .andExpect(jsonPath("$.errors[?(@.field == 'identifier')].code").value(org.hamcrest.Matchers.hasItem("RequiredLoginIdentifier")))
        assertFalse(hooks.attempts.isNotEmpty(), "a malformed request never reaches the hooks")
    }

    @Test
    fun `the limit key handed to the hooks is shared inside one IPv6 64-bit prefix while the audit ip stays the full address`() {
        login("""{"email":"user@example.com","password":"password"}""", ip = "2001:db8:c:2::7").andExpect(status().isOk)
        login("""{"email":"user@example.com","password":"password"}""", ip = "2001:db8:c:2::8").andExpect(status().isOk)
        login("""{"email":"user@example.com","password":"password"}""", ip = "2001:db8:c:3::7").andExpect(status().isOk)
        val (a, b, other) = hooks.attempts.toList()
        assertEquals("2001:db8:c:2::7", a.clientIp)
        assertEquals(a.limitKey, b.limitKey)
        assertNotEquals(a.limitKey, other.limitKey)
    }
}
