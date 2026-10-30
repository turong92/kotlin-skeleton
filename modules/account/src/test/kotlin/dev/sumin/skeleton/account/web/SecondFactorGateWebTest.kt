package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingEvents
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.accounttest.FakeSessionBeans
import dev.sumin.skeleton.accounttest.FakeSessionBook
import dev.sumin.skeleton.accounttest.FakeSocialBeans
import dev.sumin.skeleton.auth.api.SecondFactorChallenge
import dev.sumin.skeleton.auth.api.SecondFactorGate
import dev.sumin.skeleton.auth.account.AuthAccount
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 두 번째 단계 고리가 차면 이 모듈이 서 있는 로그인 길(비밀번호 · 소셜 · 탈퇴 취소)이 모두 403 `AUTH.MFA_REQUIRED` 이고 세션 · 토큰이 안 생긴다 — 매직 링크는 `auth-magic-link` 의 시험 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
        "skeleton.account.deletion.self-restore=true",
        "skeleton.auth-social.providers.fakeidp.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, FakeSocialBeans::class, FakeSessionBeans::class, SecondFactorGateWebTest.GateBeans::class)
@ExtendWith(OutputCaptureExtension::class)
class SecondFactorGateWebTest {
    class SwitchedGate : SecondFactorGate {
        @Volatile var on = false
        override fun enrolled(account: AuthAccount) = on
        override fun challenge(account: AuthAccount) = if (on) SecondFactorChallenge(TOKEN, listOf("totp", "recovery_code"), Instant.parse("2031-01-01T00:05:00Z")) else null
    }

    @TestConfiguration(proxyBeanMethods = false)
    class GateBeans {
        @Bean fun switchedGate(): SwitchedGate = SwitchedGate()
        @Bean fun recordedEvents(): RecordingEvents = RecordingEvents()
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var gate: SwitchedGate
    @Autowired lateinit var sessions: FakeSessionBook
    @Autowired lateinit var events: RecordingEvents

    @BeforeEach fun clear() { mail.sent.clear(); gate.on = false }
    @AfterEach fun off() { gate.on = false }

    private fun post(url: String, body: String, auth: String? = null) =
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body).also { b -> auth?.let { b.header("Authorization", it) } }.also { b -> if (url.endsWith("/account/delete")) b.header("Idempotency-Key", java.util.UUID.randomUUID().toString()) })

    private fun register(): String {
        val email = "u${System.nanoTime()}@example.com"
        val id = JsonPath.read<String>(post("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon"}""").andExpect(status().isAccepted).andReturn().response.contentAsString, "$.value.signUpId")
        post("/api/v1/auth/verify-email", """{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""").andExpect(status().isOk)
        return email
    }

    private fun login(email: String): MvcResult = post("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn()
    private fun accountIdOf(r: MvcResult) = JsonPath.read<String>(r.response.contentAsString, "$.value.principal.accountId")

    private fun assertChallenge(r: MvcResult) {
        assertEquals(403, r.response.status)
        val body = r.response.contentAsString
        assertEquals("AUTH.MFA_REQUIRED", JsonPath.read<String>(body, "$.code"))
        assertEquals(TOKEN, JsonPath.read<String>(body, "$.data.mfaToken"))
        assertEquals(listOf("totp", "recovery_code"), JsonPath.read<List<String>>(body, "$.data.methods"))
        assertEquals("2031-01-01T00:05:00Z", JsonPath.read<String>(body, "$.data.expiresAt"))
        assertFalse("accessToken" in body || "refreshToken" in body, "no token on the first step")
    }

    @Test
    fun `password login - with the gate filled it is a 403 challenge without session or token, and the first step is recorded`(log: CapturedOutput) {
        val email = register()
        val accountId = accountIdOf(login(email))
        val sessionsBefore = sessions.sessionsOf(accountId)
        events.all.clear()

        gate.on = true
        assertChallenge(login(email))
        assertEquals(sessionsBefore, sessions.sessionsOf(accountId), "no session was opened")
        assertEquals(listOf(AccountEventType.LOGIN_SUCCESS), events.types(), "the first step counts as the sign-in")
        assertEquals(401, post("/api/v1/auth/login", """{"email":"$email","password":"wrong-password-1"}""").andReturn().response.status, "a wrong password never reaches the gate")

        assertFalse(TOKEN in log.all, "the mfaToken is in no log line")
        assertFalse(events.all.any { TOKEN in it.toString() }, "the mfaToken is in no account event")

        gate.on = false
        assertEquals(200, login(email).response.status, "the gate empty again: login is as it was")
    }

    @Test
    fun `social login - a returning social account meets the same challenge`() {
        val accountId = accountIdOf(post("/api/v1/auth/social/fakeidp/login", """{"authorizationCode":"c-new"}""").andExpect(status().isOk).andReturn())
        val sessionsBefore = sessions.sessionsOf(accountId)
        gate.on = true
        assertChallenge(post("/api/v1/auth/social/fakeidp/login", """{"authorizationCode":"c-new"}""").andReturn())
        assertEquals(sessionsBefore, sessions.sessionsOf(accountId), "no session was opened")
    }

    @Test
    fun `a pending deletion is not undone by the first step alone - login meets the challenge first, and a restore token minted before the second factor was on cannot cancel`() {
        val email = register()
        val auth = "Bearer " + JsonPath.read<String>(login(email).response.contentAsString, "$.value.accessToken")
        post("/api/v1/account/delete", """{"currentPassword":"tangerine-42-moon"}""", auth).andExpect(status().isAccepted)
        val oldRestoreToken = JsonPath.read<String>(login(email).response.contentAsString, "$.data.restoreToken")   // 2단계가 아직 꺼져 있을 때 받은 토큰

        gate.on = true
        assertChallenge(login(email))   // 이제는 취소 토큰이 아니라 2단계 요구가 먼저다
        post("/api/v1/account/delete/cancel", """{"restoreToken":"$oldRestoreToken"}""").andExpect(status().isGone)

        gate.on = false
        val still = login(email)
        assertEquals(403, still.response.status)
        assertEquals("AUTH.ACCOUNT_DELETION_PENDING", JsonPath.read<String>(still.response.contentAsString, "$.code"), "the deletion was not undone")
    }

    private companion object { const val TOKEN = "mfa-token-original-value-never-logged" }
}
