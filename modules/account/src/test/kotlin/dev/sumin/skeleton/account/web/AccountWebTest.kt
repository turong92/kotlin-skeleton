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

// JUnit 은 시험마다 새 인스턴스를 만든다 — 돌아가는 주소의 번호는 클래스 밖(파일 수준)에 둔다
private val nextTestIp = java.util.concurrent.atomic.AtomicInteger()

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
    private fun codeOf(kind: MailKind) = mail.of(kind).last().vars.getValue("code")
    private fun signUpIdOf(r: org.springframework.test.web.servlet.ResultActions) = JsonPath.read<String>(r.andReturn().response.contentAsString, "$.value.signUpId")
    private fun unique() = "u${System.nanoTime()}@example.com"

    // 기본 주소는 돌아가며 .100~.199 — 고정 주소(.77 등)와 겹치지 않는다(무작위는 가끔 한도가 찬 주소를 골랐다)
    private fun signUp(email: String, password: String = "tangerine-42-moon", ip: String = "198.51.100.${100 + nextTestIp.getAndIncrement() % 100}") =
        mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = ip; it }.json("""{"email":"$email","password":"$password","displayName":"Ann","locale":"ko","timeZone":"Asia/Seoul"}"""))

    private fun login(email: String, password: String = "tangerine-42-moon", ip: String = "203.0.113.${(1..250).random()}") =
        mvc.perform(post("/api/v1/auth/login").with { it.remoteAddr = ip; it }.json("""{"email":"$email","password":"$password"}""")).andReturn()

    private fun registered(email: String = unique(), password: String = "tangerine-42-moon"): String {
        val id = signUpIdOf(signUp(email, password).andExpect(status().isAccepted))
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"${codeOf(MailKind.VERIFY_CODE)}"}""")).andExpect(status().isOk)
        return email
    }

    @Test
    fun `sign-up answers 202 with an opaque attempt id identically for a new, an in-flight and a registered address`() {
        val email = unique()
        val first = signUp(email).andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT")).andExpect(jsonPath("$.value.signUpId").isString).andReturn()
        val inFlight = signUp(email).andExpect(status().isAccepted).andReturn()
        assertEquals(2, mail.of(MailKind.VERIFY_CODE).size, "an in-flight attempt does not block or overwrite another one: each gets its own code")
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"${JsonPath.read<String>(inFlight.response.contentAsString, "$.value.signUpId")}","code":"${codeOf(MailKind.VERIFY_CODE)}"}""")).andExpect(status().isOk)
        mail.sent.clear()
        val registered = signUp(email).andExpect(status().isAccepted).andReturn()
        fun shape(r: MvcResult) = JsonPath.read<Map<String, Any>>(r.response.contentAsString, "$.value").let { it["status"] to (it["signUpId"] as String).length }
        assertEquals(shape(first), shape(registered))
        assertEquals(0, mail.of(MailKind.VERIFY_CODE).size, "a registered address gets no code")
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
    fun `the full journey - sign up creates no account, the code creates it and signs in, me`() {
        val email = unique()
        val id = signUpIdOf(signUp(email).andExpect(status().isAccepted))
        login(email).also {
            assertEquals(401, it.response.status, "there is no account yet - not even a pending one")
            assertEquals("AUTH.INVALID_CREDENTIALS", JsonPath.read<String>(it.response.contentAsString, "$.code"))
        }
        val code = codeOf(MailKind.VERIFY_CODE)
        val wrong = if (code == "000000") "000001" else "000000"
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"$wrong"}""")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ACCOUNT.CODE_INVALID")).andExpect(jsonPath("$.data.attemptsLeft").value(4))
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"12345"}""")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        val verified = mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"$code"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.accessToken").isString).andExpect(jsonPath("$.value.principal.email").value(email)).andReturn()
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$id","code":"$code"}""")).andExpect(status().isGone).andExpect(jsonPath("$.code").value("ACCOUNT.CODE_EXPIRED"))

        mvc.perform(get("/api/v1/account/me").header("Authorization", bearer(verified))).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.email").value(email))
            .andExpect(jsonPath("$.value.emailVerified").value(true))
            .andExpect(jsonPath("$.value.hasPassword").value(true))
            .andExpect(jsonPath("$.value.roles[0]").value("USER"))
            .andExpect(jsonPath("$.value.locale").value("ko"))
            .andExpect(jsonPath("$.value.methods[0].method").value("password"))
        assertEquals(200, login(email).response.status)
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
    fun `an authentication failure is never cached under an Idempotency-Key - the retry with the right password runs, a success is still replayed`() {
        val email = registered()
        val auth = bearer(login(email))
        val key = java.util.UUID.randomUUID().toString()
        val target = unique()
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", key).json("""{"newEmail":"$target","currentPassword":"wrong-password-1"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        // the mistyped password must not stick to the key: another attempt really executes (and fails again on its own merits - not a stored replay)
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", key).json("""{"newEmail":"$target","currentPassword":"another-wrong-pass-2"}"""))
            .andExpect(status().isBadRequest).andExpect(header().string("X-Idempotency-Replayed", "false"))
        // the right password with the same key now works
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", key).json("""{"newEmail":"$target","currentPassword":"tangerine-42-moon"}"""))
            .andExpect(status().isAccepted).andExpect(header().string("X-Idempotency-Replayed", "false"))
        // and a success is replayed, not executed twice
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", key).json("""{"newEmail":"$target","currentPassword":"tangerine-42-moon"}"""))
            .andExpect(status().isAccepted).andExpect(header().string("X-Idempotency-Replayed", "true"))
        assertEquals(1, mail.of(MailKind.EMAIL_CHANGE_CODE).size)
    }

    @Test
    fun `an email change is confirmed with the code mailed to the new address, entered in the signed-in session, and moves the login`() {
        val email = registered()
        val newEmail = unique()
        val auth = bearer(login(email))
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).idem().json("""{"newEmail":"$newEmail","currentPassword":"tangerine-42-moon"}"""))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT"))
        assertEquals(newEmail, mail.of(MailKind.EMAIL_CHANGE_CODE).last().to)
        val code = codeOf(MailKind.EMAIL_CHANGE_CODE)
        mvc.perform(post("/api/v1/account/email/change/confirm").json("""{"code":"$code"}""")).andExpect(status().isUnauthorized)
        val wrong = if (code == "000000") "000001" else "000000"
        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).json("""{"code":"$wrong"}""")).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ACCOUNT.CODE_INVALID")).andExpect(jsonPath("$.data.attemptsLeft").value(4))
        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).json("""{"code":"$code"}""")).andExpect(status().isNoContent)
        assertEquals(401, login(email).response.status)
        assertEquals(200, login(newEmail).response.status)
        // the public link endpoint of the old contract is gone
        mvc.perform(post("/api/v1/auth/confirm-email-change").json("""{"token":"x"}""")).andExpect(status().is4xxClientError).andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("ACCOUNT.TOKEN_INVALID")))
    }

    @Test
    fun `profile update validates and the last sign-in method cannot be removed`() {
        val email = registered()
        val auth = bearer(login(email))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"displayName":"Ann B","timeZone":"America/New_York"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.value.displayName").value("Ann B")).andExpect(jsonPath("$.value.timeZone").value("America/New_York"))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).json("""{"timeZone":"Mars/Olympus"}""")).andExpect(status().isBadRequest)

        val id = JsonPath.read<String>(mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString, "$.values[0].id")
        mvc.perform(delete("/api/v1/account/identities/$id").header("Authorization", auth)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))   // unlinking is a re-authenticated action
        mvc.perform(delete("/api/v1/account/identities/$id").header("Authorization", auth).json("""{"currentPassword":"tangerine-42-moon"}""")).andExpect(status().isConflict)
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

    /** 부트스트랩 관리자 — 클래스의 시험들이 한 컨텍스트를 나눠 쓰므로 이미 가입했으면 로그인만 한다 */
    private fun bossAuth(): String {
        val first = login("boss@example.com")
        if (first.response.status == 200) return bearer(first)
        registered("boss@example.com")
        return bearer(login("boss@example.com"))
    }

    @Test
    fun `the configured bootstrap address becomes admin on its first verified sign-in and can run the admin API`() {
        val auth = bossAuth()
        // paging is validated like every other list, not silently clamped
        mvc.perform(get("/api/v1/admin/accounts").param("size", "500").header("Authorization", auth)).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
        mvc.perform(get("/api/v1/admin/accounts").param("page", "-1").header("Authorization", auth)).andExpect(status().isBadRequest)
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
    fun `an admin erases a suspended account, sees it as ERASED without personal data, and lists and lifts the hash-only blocks`() {
        val auth = bossAuth()
        val victim = registered()
        val id = JsonPath.read<String>(mvc.perform(get("/api/v1/admin/accounts").param("email", victim).header("Authorization", auth)).andReturn().response.contentAsString, "$.values[0].id")
        mvc.perform(post("/api/v1/admin/accounts/$id/erase").header("Authorization", auth).json("""{"reason":"fraud-ring"}""")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.NOT_SUSPENDED"))
        mvc.perform(post("/api/v1/admin/accounts/$id/suspend").header("Authorization", auth).json("""{"reason":"abuse"}""")).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/admin/accounts/$id/erase").header("Authorization", auth).json("""{"reason":"fraud-ring"}""")).andExpect(status().isNoContent)

        mvc.perform(get("/api/v1/admin/accounts/$id").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.status").value("ERASED")).andExpect(jsonPath("$.value.email").doesNotExist()).andExpect(jsonPath("$.value.displayName").doesNotExist())
        mvc.perform(get("/api/v1/admin/accounts").param("status", "ERASED").header("Authorization", auth)).andExpect(jsonPath("$.values[?(@.id=='$id')]").isNotEmpty)
        mvc.perform(get("/api/v1/admin/accounts").header("Authorization", auth)).andExpect(jsonPath("$.values[?(@.id=='$id')]").isEmpty)
        mvc.perform(post("/api/v1/admin/accounts/$id/restore").header("Authorization", auth)).andExpect(status().isGone).andExpect(jsonPath("$.code").value("ACCOUNT.ERASED"))
        mvc.perform(post("/api/v1/admin/accounts/$id/erase").header("Authorization", auth).json("{}")).andExpect(status().isGone)

        val listed = mvc.perform(get("/api/v1/admin/accounts/blocks").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString
        val mine = JsonPath.read<List<Map<String, Any?>>>(listed, "$.values[?(@.reason=='fraud-ring')]")
        assertEquals(1, mine.size)
        assertEquals("email", mine.single()["kind"])
        assertTrue(!listed.contains(victim) && !listed.contains("hash"), "the list never carries the address or the hash")

        // the sign-up request answers as always; only the mailbox proof is refused
        val signUpId = signUpIdOf(signUp(victim).andExpect(status().isAccepted))
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$signUpId","code":"${codeOf(MailKind.VERIFY_CODE)}"}""")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.REGISTRATION_BLOCKED"))

        mvc.perform(delete("/api/v1/admin/accounts/blocks/${mine.single()["id"]}").header("Authorization", auth)).andExpect(status().isNoContent)
        mvc.perform(delete("/api/v1/admin/accounts/blocks/${mine.single()["id"]}").header("Authorization", auth)).andExpect(status().isNotFound)
        val again = signUpIdOf(signUp(victim).andExpect(status().isAccepted))
        mvc.perform(post("/api/v1/auth/verify-email").json("""{"signUpId":"$again","code":"${codeOf(MailKind.VERIFY_CODE)}"}""")).andExpect(status().isOk)
    }

    @Test
    fun `a normal user gets 403 on the admin API and anonymous gets 401`() {
        val auth = bearer(login(registered()))
        mvc.perform(get("/api/v1/admin/accounts").header("Authorization", auth)).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/admin/accounts")).andExpect(status().isUnauthorized)
    }
}
