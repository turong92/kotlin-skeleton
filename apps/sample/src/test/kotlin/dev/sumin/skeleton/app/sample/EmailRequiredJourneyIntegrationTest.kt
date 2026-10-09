package dev.sumin.skeleton.app.sample

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import java.time.Instant
import java.util.UUID
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * "로그인 직후" 방식(`skeleton.account.email.required-when=SIGN_IN` — 이 앱의 yml 은 WRITE 라 설정 한 줄로 되돌린다; 기능은 그대로이고 방식만 다르다. "쓰기 때" 길은 EmailOnWriteJourneyIntegrationTest).
 * 이 앱은 로그인 아이디도 켠 채라 아이디 게이트가 이메일 게이트 앞에 선다 — 만들어 둔 계정은 아이디만 먼저 정해(준비 단계) 이메일 게이트를 만난다.
 * 샘플 앱(`skeleton.account.email.required: true`)에서 이메일 없이 만들어진 계정(Kakao · Naver · X · LINE …)의 길 — 진짜 PostgreSQL · 보안 체인 · 모든 모듈:
 * 노트 · 게시판 · 작성자 프로필이 403 ACCOUNT.EMAIL_REQUIRED, 허용 목록은 열림, 재인증 없이 첫 주소 등록, 그 뒤 약관 재동의(순서: 주소가 먼저), 이미 쓰인 주소의 무노출.
 */
@SpringBootTest(properties = ["spring.config.import=classpath:test-seeds.yml", "skeleton.account.mail.link-base-url=https://app.example.com", "skeleton.legal.reconsent.enabled=true", "skeleton.account.email.required-when=SIGN_IN"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, EmailRequiredJourneyIntegrationTest.Beans::class)
class EmailRequiredJourneyIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    class Beans {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
        @Bean fun fakeStorage(): dev.sumin.skeleton.app.sample.notes.FakePresignedStorage = dev.sumin.skeleton.app.sample.notes.FakePresignedStorage()
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var beans: Beans
    @Autowired lateinit var accounts: AccountRepository
    @Autowired lateinit var authAccounts: AuthAccountRepository
    @Autowired lateinit var tokens: AuthTokenResponseFactory

    /** 방금 소셜로 로그인한 이메일 없는 계정 (Kakao 같은) — 제공자 서버 없이 같은 상태를 만든다 */
    private fun addressLess(): String {
        val id = "acc_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val now = Instant.now()
        check(accounts.insert(Account(id, null, false, AccountStatus.ACTIVE, setOf("USER"), "Kakao ${id.takeLast(4)}", null, null, now, now, lastLoginAt = now), listOf(Identity("idn_${id.takeLast(12)}", id, "kakao", "kakao-$id", true, createdAt = now))))
        val bearer = tokens.issue(authAccounts.findBy(AccountIdentifier(accountId = id))!!).accessToken
        // 준비 단계: 아이디 게이트(-110)가 이메일 게이트(-100) 앞이다 — 아이디를 먼저 정해야 이 시험의 주제(이메일 게이트)가 보인다. 이메일 없이도 정할 수 있다
        post("/api/v1/account/login-id", """{"loginId":"kakao.${id.takeLast(10)}"}""", bearer).andExpect { status { isOk() } }
        return bearer
    }

    private fun get(path: String, bearer: String) = mvc.get(path) { header("Authorization", "Bearer $bearer") }

    private fun post(path: String, body: String, bearer: String, idempotent: Boolean = false) = mvc.post(path) {
        if (path == "/api/v1/account/login-id") with { it.remoteAddr = TestClientIps.fresh(); it }   // 정하기는 IP 별로도 센다 — 시험마다 새 주소
        contentType = MediaType.APPLICATION_JSON; content = body; header("Authorization", "Bearer $bearer")
        if (idempotent) header("Idempotency-Key", UUID.randomUUID().toString())
    }

    private fun blocked(r: ResultActionsDsl, code: String) = r.andExpect { status { isForbidden() }; jsonPath("$.code") { value(code) } }

    @Test
    fun `notes, boards and author profiles refuse an address-less account, the allow list stays open`() {
        val bearer = addressLess()

        blocked(get("/api/v1/notes", bearer), "ACCOUNT.EMAIL_REQUIRED")
        blocked(get("/api/v1/boards/free/posts", bearer), "ACCOUNT.EMAIL_REQUIRED")
        blocked(get("/api/v1/author-profiles", bearer), "ACCOUNT.EMAIL_REQUIRED")
        blocked(get("/api/v1/account/identities", bearer), "ACCOUNT.EMAIL_REQUIRED")

        get("/api/v1/account/me", bearer).andExpect { status { isOk() }; jsonPath("$.value.emailRequired") { value(true) } }
        get("/api/v1/auth/me", bearer).andExpect { status { isOk() } }
        get("/api/v1/legal/consents/me", bearer).andExpect { status { isOk() } }
        mvc.get("/api/v1/auth/methods").andExpect { status { isOk() } }
        post("/api/v1/account/delete/confirmation", "{}", bearer).andExpect { status { isAccepted() } }
    }

    @Test
    fun `registering the first address needs no re-authentication - and then the legal re-consent comes next, the address first`() {
        val bearer = addressLess()
        val email = "first-${System.nanoTime()}@example.com"

        val asked = post("/api/v1/account/email/change", """{"newEmail":"$email"}""", bearer, idempotent = true)
            .andExpect { status { isAccepted() }; jsonPath("$.value.status") { value("VERIFICATION_SENT") }; jsonPath("$.value.expiresAt") { exists() }; jsonPath("$.value.resendAvailableAt") { exists() } }
        val code = beans.sent.last { it.kind == MailKind.EMAIL_CHANGE_CODE && it.to == email }.vars.getValue("code")
        blocked(get("/api/v1/notes", bearer), "ACCOUNT.EMAIL_REQUIRED")   // typing the address is not enough - the code must come back

        post("/api/v1/account/email/change/confirm", """{"code":"$code"}""", bearer).andExpect { status { isNoContent() } }

        // two filters on: the address was the first gate, the missing consent is the second
        blocked(get("/api/v1/notes", bearer), "LEGAL.RECONSENT_REQUIRED")
        get("/api/v1/account/me", bearer).andExpect { status { isOk() }; jsonPath("$.value.email") { value(email) }; jsonPath("$.value.emailVerified") { value(true) }; jsonPath("$.value.emailRequired") { value(false) } }
        post("/api/v1/legal/consents", """{"consents":[{"type":"terms","version":"sample-1"},{"type":"privacy","version":"sample-1"}],"source":"first-sign-in"}""", bearer)
            .andExpect { status { isOk() } }
        get("/api/v1/notes", bearer).andExpect { status { isOk() } }
        assertTrue(asked.andReturn().response.contentAsString.isNotBlank())
    }

    @Test
    fun `an address that already has an account answers the same 202, mails its owner, and never gets a code`() {
        val bearer = addressLess()
        assertTrue(accounts.findByEmail("user@example.com") != null, "the address really belongs to a seeded account")
        beans.sent.clear()

        val taken = post("/api/v1/account/email/change", """{"newEmail":"user@example.com"}""", bearer, idempotent = true).andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        assertTrue(beans.sent.any { it.kind == MailKind.EMAIL_REGISTRATION_ATTEMPT && it.to == "user@example.com" }, "the owner is told, in the first-registration wording")
        assertTrue(beans.sent.none { it.kind == MailKind.ALREADY_REGISTERED }, "not the sign-up wording")
        assertTrue(beans.sent.none { it.kind == MailKind.EMAIL_CHANGE_CODE }, "no code anywhere")
        // the OPEN request is the taken one right now (a later request would replace it) - its code can never be known, so any guess fails
        post("/api/v1/account/email/change/confirm", """{"code":"000000"}""", bearer).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("ACCOUNT.CODE_INVALID") } }
        blocked(get("/api/v1/notes", bearer), "ACCOUNT.EMAIL_REQUIRED")

        val free = post("/api/v1/account/email/change", """{"newEmail":"free-${System.nanoTime()}@example.com"}""", bearer, idempotent = true).andExpect { status { isAccepted() } }.andReturn().response.contentAsString
        assertEquals(JsonPath.read<Map<String, Any?>>(free, "$.value").keys, JsonPath.read<Map<String, Any?>>(taken, "$.value").keys, "the screen cannot tell the two apart")
        assertTrue(beans.sent.any { it.kind == MailKind.EMAIL_CHANGE_CODE }, "only the free address got a code")
    }
}
