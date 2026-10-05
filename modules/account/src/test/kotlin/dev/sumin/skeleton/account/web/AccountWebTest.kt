package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.admin.enabled=true",
        "skeleton.account.bootstrap.admin-email=boss@example.com",
        "skeleton.account.login.per-account=5",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class AccountWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer

    @BeforeEach fun clear() { mail.sent.clear() }

    /** 삭제 · 이메일 변경은 @IdempotentOperation — idempotency 모듈이 클래스패스에 있으면 Idempotency-Key 가 필수다 */
    private fun MockHttpServletRequestBuilder.idem() = header("Idempotency-Key", java.util.UUID.randomUUID().toString())
    private fun MockHttpServletRequestBuilder.json(body: String) = contentType(MediaType.APPLICATION_JSON).content(body)
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    private fun tokenOf(kind: MailKind) = mail.tokenOf(mail.of(kind).last())
    private fun unique() = "u${System.nanoTime()}@example.com"

    private fun signUp(email: String, password: String = "tangerine-42-moon", ip: String = "198.51.100.${(1..250).random()}") =
        mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = ip; it }.json("""{"email":"$email","password":"$password","displayName":"Ann","locale":"ko","timeZone":"Asia/Seoul"}"""))

    private fun login(email: String, password: String = "tangerine-42-moon", ip: String = "203.0.113.${(1..250).random()}") =
        mvc.perform(post("/api/v1/auth/login").with { it.remoteAddr = ip; it }.json("""{"email":"$email","password":"$password"}""")).andReturn()

    private fun registered(email: String = unique(), password: String = "tangerine-42-moon"): String {
        signUp(email, password).andExpect(status().isAccepted)
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"token":"${tokenOf(MailKind.VERIFY_EMAIL)}"}""")).andExpect(status().isOk)
        return email
    }

    @Test
    fun `sign-up answers 202 identically for a new and an existing address`() {
        val email = unique()
        val first = signUp(email).andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT")).andReturn()
        val again = signUp(email).andExpect(status().isAccepted).andReturn()
        fun body(r: MvcResult) = JsonPath.read<Map<String, Any>>(r.response.contentAsString, "$.value")
        assertEquals(body(first), body(again))
        assertEquals(1, mail.of(MailKind.VERIFY_EMAIL).size)
        assertEquals(1, mail.of(MailKind.ALREADY_REGISTERED).size)
    }

    @Test
    fun `a weak password is a 400 with the violation codes, bad input is a validation error`() {
        signUp(unique(), "short1").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ACCOUNT.PASSWORD_POLICY"))
            .andExpect(jsonPath("$.data.violations[0]").value("TOO_SHORT"))
        mvc.perform(post("/api/v1/account/sign-up").json("""{"email":"not-an-email","password":"tangerine-42-moon"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
    }

    @Test
    fun `the full journey - sign up, blocked until verified, verify, log in, me`() {
        val email = unique()
        signUp(email).andExpect(status().isAccepted)
        login(email).also {
            assertEquals(403, it.response.status)
            assertEquals("AUTH.EMAIL_NOT_VERIFIED", JsonPath.read<String>(it.response.contentAsString, "$.code"))
        }
        val token = tokenOf(MailKind.VERIFY_EMAIL)
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"token":"$token"}""")).andExpect(status().isOk).andExpect(jsonPath("$.value.status").value("VERIFIED"))
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"token":"$token"}""")).andExpect(status().isGone).andExpect(jsonPath("$.code").value("ACCOUNT.TOKEN_INVALID"))

        val ok = login(email)
        assertEquals(200, ok.response.status)
        mvc.perform(get("/api/v1/account/me").header("Authorization", bearer(ok))).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.email").value(email))
            .andExpect(jsonPath("$.value.emailVerified").value(true))
            .andExpect(jsonPath("$.value.hasPassword").value(true))
            .andExpect(jsonPath("$.value.roles[0]").value("USER"))
            .andExpect(jsonPath("$.value.locale").value("ko"))
            .andExpect(jsonPath("$.value.methods[0].method").value("password"))
    }

    @Test
    fun `account endpoints need a bearer token, public ones do not`() {
        mvc.perform(get("/api/v1/account/me")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/account/password/change").json("""{"newPassword":"a-brand-new-pass-7"}""")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/account/password/policy")).andExpect(status().isOk).andExpect(jsonPath("$.value.minLength").value(10))
    }

    @Test
    fun `forgot is 202 and identical for unknown addresses, reset signs in with the new password only`() {
        val email = registered()
        val known = mvc.perform(post("/api/v1/account/password/forgot").json("""{"email":"$email"}""")).andExpect(status().isAccepted).andReturn()
        val unknown = mvc.perform(post("/api/v1/account/password/forgot").json("""{"email":"${unique()}"}""")).andExpect(status().isAccepted).andReturn()
        assertEquals(JsonPath.read<Any>(known.response.contentAsString, "$.value"), JsonPath.read<Any>(unknown.response.contentAsString, "$.value"))
        assertEquals(1, mail.of(MailKind.PASSWORD_RESET).size)

        val token = tokenOf(MailKind.PASSWORD_RESET)
        mvc.perform(post("/api/v1/account/password/reset").json("""{"token":"$token","newPassword":"weak"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/account/password/reset").json("""{"token":"$token","newPassword":"a-brand-new-pass-7"}""")).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/account/password/reset").json("""{"token":"$token","newPassword":"another-new-pass-8"}""")).andExpect(status().isGone)
        assertEquals(401, login(email).response.status)
        assertEquals(200, login(email, "a-brand-new-pass-7").response.status)
    }

    @Test
    fun `change password demands the current one`() {
        val email = registered()
        val auth = bearer(login(email))
        mvc.perform(post("/api/v1/account/password/change").header("Authorization", auth).json("""{"currentPassword":"nope-nope-1","newPassword":"a-brand-new-pass-7"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        mvc.perform(post("/api/v1/account/password/change").header("Authorization", auth).json("""{"currentPassword":"tangerine-42-moon","newPassword":"a-brand-new-pass-7"}"""))
            .andExpect(status().isNoContent)
        assertEquals(200, login(email, "a-brand-new-pass-7").response.status)
    }

    @Test
    fun `an email change is confirmed from the new address and moves the login`() {
        val email = registered()
        val newEmail = unique()
        val auth = bearer(login(email))
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).idem().json("""{"newEmail":"$newEmail","currentPassword":"tangerine-42-moon"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT"))
        mvc.perform(post("/api/v1/auth/confirm-email-change").json("""{"token":"${tokenOf(MailKind.EMAIL_CHANGE_CONFIRM)}"}""")).andExpect(status().isNoContent)
        assertEquals(401, login(email).response.status)
        assertEquals(200, login(newEmail).response.status)
    }

    @Test
    fun `profile update validates and the last sign-in method cannot be removed`() {
        val email = registered()
        val auth = bearer(login(email))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"displayName":"Ann B","timeZone":"America/New_York"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.displayName").value("Ann B")).andExpect(jsonPath("$.value.timeZone").value("America/New_York"))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"timeZone":"Mars/Olympus"}""")).andExpect(status().isBadRequest)

        val id = JsonPath.read<String>(mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString, "$.values[0].id")
        mvc.perform(delete("/api/v1/account/identities/$id").header("Authorization", auth)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ACCOUNT.LAST_SIGN_IN_METHOD"))
    }

    @Test
    fun `deleting needs the password, then login stops working`() {
        val email = registered()
        val auth = bearer(login(email))
        mvc.perform(post("/api/v1/account/delete").header("Authorization", auth).idem().json("""{"currentPassword":"nope-nope-1"}""")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ACCOUNT.REAUTH_FAILED"))
        mvc.perform(post("/api/v1/account/delete").header("Authorization", auth).idem().json("""{"currentPassword":"tangerine-42-moon"}""")).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.value.status").value("DELETION_SCHEDULED")).andExpect(jsonPath("$.value.purgeAfter").exists())
        assertEquals(401, login(email).response.status)
    }

    @Test
    fun `login attempts are throttled with 429, Retry-After and the AUTH code`() {
        val email = unique()
        repeat(5) { assertEquals(401, login(email, "wrong-password-1", ip = "198.51.100.77").response.status) }
        val r = login(email, "wrong-password-1", ip = "198.51.100.77")
        assertEquals(429, r.response.status)
        assertEquals("AUTH.TOO_MANY_ATTEMPTS", JsonPath.read<String>(r.response.contentAsString, "$.code"))
        assertNotNull(r.response.getHeader("Retry-After"))
        assertTrue(r.response.getHeader("Retry-After")!!.toLong() >= 1)
    }

    // ---- admin

    @Test
    fun `the configured bootstrap address becomes admin on its first verified sign-in and can run the admin API`() {
        val boss = registered("boss@example.com")
        val auth = bearer(login(boss))
        val victim = registered()
        val id = JsonPath.read<String>(
            mvc.perform(get("/api/v1/admin/accounts").param("email", victim).header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString, "$.values[0].id",
        )
        mvc.perform(post("/api/v1/admin/accounts/$id/suspend").header("Authorization", auth).json("""{"reason":"abuse"}""")).andExpect(status().isNoContent)
        val blocked = login(victim)
        assertEquals(403, blocked.response.status)
        assertEquals("AUTH.ACCOUNT_SUSPENDED", JsonPath.read<String>(blocked.response.contentAsString, "$.code"))
        mvc.perform(post("/api/v1/admin/accounts/$id/unsuspend").header("Authorization", auth)).andExpect(status().isNoContent)
        assertEquals(200, login(victim).response.status)
        mvc.perform(put("/api/v1/admin/accounts/$id/roles/MODERATOR").header("Authorization", auth)).andExpect(status().isNoContent)
        assertTrue(JsonPath.read<List<String>>(login(victim).response.contentAsString, "$.value.principal.roles").contains("MODERATOR"))
    }

    @Test
    fun `a normal user gets 403 on the admin API and anonymous gets 401`() {
        val auth = bearer(login(registered()))
        mvc.perform(get("/api/v1/admin/accounts").header("Authorization", auth)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/accounts")).andExpect(status().isUnauthorized)
    }
}
