package dev.sumin.skeleton.app.api

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 스타터가 "켜기만 해도 서비스 구색" 이라는 증거 — 가입 → 이메일 확인 → 로그인 → 새로고침 → 비밀번호 변경 → 세션 → 삭제가 이 최소 조립(메일 모듈 없음)에서 돈다.
 * 메일은 기록해 두었다가 링크의 토큰만 꺼내 쓴다 (진짜 메일 길은 `notification-mail` 이고 이 스타터에는 없다).
 */
@SpringBootTest(properties = ["skeleton.auth-session.reuse-grace=0s", "skeleton.account.password.bcrypt-strength=4", "skeleton.account.mail.link-base-url=https://app.example.com"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AccountJourneyIntegrationTest.Mails::class)
class AccountJourneyIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Mails {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
        val events = CopyOnWriteArrayList<dev.sumin.skeleton.account.events.AccountEvent>()
        @Bean fun recordingEvents(): dev.sumin.skeleton.account.events.AccountEventListener = dev.sumin.skeleton.account.events.AccountEventListener { events += it }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails

    private fun json(path: String, body: String, bearer: String? = null, headers: Map<String, String> = emptyMap()) =
        mvc.post(path) {
            contentType = MediaType.APPLICATION_JSON; content = body
            bearer?.let { header("Authorization", "Bearer $it") }
            headers.forEach { (k, v) -> header(k, v) }
        }

    private fun tokenOf(kind: String) = mails.sent.last { it.kind.name == kind }.link!!.substringAfter("token=")
    private fun codeOf(kind: String, to: String) = mails.sent.last { it.kind.name == kind && it.to == to }.vars.getValue("code")

    @Test
    fun `sign up, verify, log in, refresh, change password, list sessions, delete`() {
        val email = "journey-${System.nanoTime()}@example.com"
        val signUp = json("/api/v1/account/sign-up", """{"email":"$email","password":"tangerine-42-moon","consents":[{"type":"terms","version":"template-1"},{"type":"privacy","version":"template-1"}]}""").andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        json("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andExpect { status { isUnauthorized() } }   // no account until the code is entered
        val verified = json("/api/v1/auth/verify-email", """{"signUpId":"${JsonPath.read<String>(signUp, "$.value.signUpId")}","code":"${codeOf("VERIFY_CODE", email)}"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(JsonPath.read<String>(verified, "$.value.refreshToken").startsWith("r1."), "the verifying browser is signed in at once")

        val login = json("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val access = JsonPath.read<String>(login, "$.value.accessToken")
        val refresh = JsonPath.read<String>(login, "$.value.refreshToken")
        assertTrue(refresh.startsWith("r1."))

        val next = json("/api/v1/auth/refresh", """{"refreshToken":"$refresh"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(JsonPath.read<String>(next, "$.value.refreshToken") != refresh)
        json("/api/v1/auth/refresh", """{"refreshToken":"$refresh"}""").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.REFRESH_REUSED") } }

        val relogin = json("/api/v1/auth/login", """{"email":"$email","password":"tangerine-42-moon"}""").andReturn().response.contentAsString
        val bearer = JsonPath.read<String>(relogin, "$.value.accessToken")
        json("/api/v1/account/password/change", """{"currentPassword":"tangerine-42-moon","newPassword":"a-brand-new-pass-7"}""", bearer).andExpect { status { isNoContent() } }
        mvc.get("/api/v1/auth/sessions") { header("Authorization", "Bearer $bearer") }.andExpect { status { isOk() }; jsonPath("$.values[0].current") { value(true) } }
        json("/api/v1/auth/login", """{"email":"$email","password":"a-brand-new-pass-7"}""").andExpect { status { isOk() } }

        val auth = JsonPath.read<String>(json("/api/v1/auth/login", """{"email":"$email","password":"a-brand-new-pass-7"}""").andReturn().response.contentAsString, "$.value.accessToken")
        json("/api/v1/account/delete", """{"currentPassword":"a-brand-new-pass-7"}""", auth).andExpect { status { isAccepted() }; jsonPath("$.value.status") { value("DELETION_SCHEDULED") } }
        json("/api/v1/auth/login", """{"email":"$email","password":"a-brand-new-pass-7"}""").andExpect { status { isUnauthorized() } }
        assertEquals(access.isNotBlank(), true)
    }

    private fun registered(email: String, password: String = "tangerine-42-moon"): String {
        val signUp = json("/api/v1/account/sign-up", """{"email":"$email","password":"$password","consents":[{"type":"terms","version":"template-1"},{"type":"privacy","version":"template-1"}]}""").andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        json("/api/v1/auth/verify-email", """{"signUpId":"${JsonPath.read<String>(signUp, "$.value.signUpId")}","code":"${codeOf("VERIFY_CODE", email)}"}""").andExpect { status { isOk() } }
        return email
    }

    private fun loginAs(email: String, password: String = "tangerine-42-moon") =
        json("/api/v1/auth/login", """{"email":"$email","password":"$password"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString

    @Test
    fun `a password reset kills every refresh token for real - the SessionRevoker adapter and the JDBC store, not a recording stub`() {
        val email = registered("reset-${System.nanoTime()}@example.com")
        val device1 = JsonPath.read<String>(loginAs(email), "$.value.refreshToken")
        val device2 = JsonPath.read<String>(loginAs(email), "$.value.refreshToken")
        json("/api/v1/account/password/forgot", """{"email":"$email"}""").andExpect { status { isAccepted() } }
        json("/api/v1/account/password/reset", """{"token":"${tokenOf("PASSWORD_RESET")}","newPassword":"a-brand-new-pass-7"}""").andExpect { status { isNoContent() } }
        json("/api/v1/auth/refresh", """{"refreshToken":"$device1"}""").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.REFRESH_INVALID") } }
        json("/api/v1/auth/refresh", """{"refreshToken":"$device2"}""").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.REFRESH_INVALID") } }
    }

    @Test
    fun `a password change signs the other devices out for real and keeps the one that changed it`() {
        val email = registered("change-${System.nanoTime()}@example.com")
        val here = loginAs(email)
        val other = JsonPath.read<String>(loginAs(email), "$.value.refreshToken")
        json("/api/v1/account/password/change", """{"currentPassword":"tangerine-42-moon","newPassword":"a-brand-new-pass-7"}""", JsonPath.read<String>(here, "$.value.accessToken")).andExpect { status { isNoContent() } }
        json("/api/v1/auth/refresh", """{"refreshToken":"$other"}""").andExpect { status { isUnauthorized() } }
        json("/api/v1/auth/refresh", """{"refreshToken":"${JsonPath.read<String>(here, "$.value.refreshToken")}"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `an email change confirmation signs the OTHER sessions out for real and keeps the confirming one`() {
        val email = registered("emailchange-${System.nanoTime()}@example.com")
        val here = loginAs(email)
        val other = JsonPath.read<String>(loginAs(email), "$.value.refreshToken")
        val target = "moved-${System.nanoTime()}@example.com"
        val access = JsonPath.read<String>(here, "$.value.accessToken")
        json("/api/v1/account/email/change", """{"newEmail":"$target","currentPassword":"tangerine-42-moon"}""", access, mapOf("Idempotency-Key" to java.util.UUID.randomUUID().toString())).andExpect { status { isAccepted() } }
        json("/api/v1/account/email/change/confirm", """{"code":"${codeOf("EMAIL_CHANGE_CODE", target)}"}""", access).andExpect { status { isNoContent() } }
        json("/api/v1/auth/refresh", """{"refreshToken":"$other"}""").andExpect { status { isUnauthorized() } }
        json("/api/v1/auth/refresh", """{"refreshToken":"${JsonPath.read<String>(here, "$.value.refreshToken")}"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `a replayed refresh token reaches the account event stream - the session module's detection is wired`() {
        val email = registered("reuse-${System.nanoTime()}@example.com")
        val first = JsonPath.read<String>(loginAs(email), "$.value.refreshToken")
        json("/api/v1/auth/refresh", """{"refreshToken":"$first"}""").andExpect { status { isOk() } }
        json("/api/v1/auth/refresh", """{"refreshToken":"$first"}""").andExpect { status { isUnauthorized() }; jsonPath("$.code") { value("AUTH.REFRESH_REUSED") } }
        assertTrue(mails.events.any { it.type == dev.sumin.skeleton.account.events.AccountEventType.REFRESH_REUSE_DETECTED }, "no REFRESH_REUSE_DETECTED event: the detection leaves no trace")
    }
}
