package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.accounttest.FakeSessionBeans
import dev.sumin.skeleton.accounttest.FakeSessionBook
import dev.sumin.skeleton.accounttest.FakeSocialBeans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** 다른 모듈의 엔드포인트를 흉내 내는 보호된 경로 — board · notes 처럼 account 를 모르는 컨트롤러 */
@RestController
class ForeignModuleController {
    @GetMapping("/api/v1/things") fun things() = mapOf("ok" to true)
    @PostMapping("/api/v1/things") fun create() = mapOf("ok" to true)
}

@TestConfiguration(proxyBeanMethods = false)
class ForeignModuleBeans {
    @Bean fun foreignModuleController() = ForeignModuleController()

    /** 함수형 라우트 — `HandlerMethod` 가 아닌 핸들러도 같은 규칙을 받아야 한다 */
    @Bean fun foreignFunctionalRoute(): org.springframework.web.servlet.function.RouterFunction<org.springframework.web.servlet.function.ServerResponse> =
        org.springframework.web.servlet.function.RouterFunctions.route()
            .GET("/api/v1/fn") { org.springframework.web.servlet.function.ServerResponse.ok().body(mapOf("ok" to true)) }
            .build()
}

/** `skeleton.account.email.required=true` — 주소 없는 계정은 허용 목록 밖의 모든 API 에서 403 `ACCOUNT.EMAIL_REQUIRED`, 첫 주소는 재인증 없이 등록 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
        "skeleton.account.email.required=true",
        "skeleton.auth-social.providers.fakeidp.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, FakeSocialBeans::class, FakeSessionBeans::class, ForeignModuleBeans::class)
class EmailRequiredWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var sessions: FakeSessionBook

    private val json = MediaType.APPLICATION_JSON
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    private fun social(code: String) = mvc.perform(post("/api/v1/auth/social/fakeidp/login").contentType(json).content("""{"authorizationCode":"$code"}""")).andExpect(status().isOk).andReturn()

    private fun passwordAccount(email: String): String {
        mail.sent.clear()
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"$email","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val verified = mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
        return bearer(verified)
    }

    private fun changeEmail(auth: String, to: String) = mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("""{"newEmail":"$to"}"""))

    @Test
    fun `an account without an address is stopped on every endpoint outside the allow list - also on other modules' endpoints`() {
        val auth = bearer(social("c-noemail-req"))

        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(delete("/api/v1/account/identities/idn_x").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
    }

    @Test
    fun `the allow list stays open - me, the email flow, sign-out and the auth endpoints, deletion`() {
        val auth = bearer(social("c-noemail-req"))

        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.emailRequired").value(true))
        mvc.perform(get("/api/v1/auth/me").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/auth/methods")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/account/delete/confirmation").header("Authorization", auth)).andExpect(status().isAccepted)
    }

    @Test
    fun `the first address is registered without re-authentication, then the account is usable`() {
        val auth = bearer(social("c-noemail-req2"))
        mail.sent.clear()

        changeEmail(auth, "Kim@Example.com").andExpect(status().isAccepted)
            .andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT"))
            .andExpect(jsonPath("$.value.expiresAt").isNotEmpty)
            .andExpect(jsonPath("$.value.resendAvailableAt").isNotEmpty)
        val sent = mail.of(MailKind.EMAIL_CHANGE_CODE).single()
        assertEquals("kim@example.com", sent.to)
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden)   // not yet - the code was not entered

        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).contentType(json).content("""{"code":"${sent.vars.getValue("code")}"}"""))
            .andExpect(status().isNoContent)

        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.email").value("kim@example.com"))
            .andExpect(jsonPath("$.value.emailVerified").value(true))
            .andExpect(jsonPath("$.value.emailRequired").value(false))
    }

    @Test
    fun `an address that already has an account answers the same 202 and mails the owner - no code, and the screen cannot tell`() {
        passwordAccount("owner@example.com")
        val auth = bearer(social("c-noemail-req3"))
        mail.sent.clear()

        val taken = changeEmail(auth, "owner@example.com").andExpect(status().isAccepted).andReturn()
        assertEquals("owner@example.com", mail.of(MailKind.EMAIL_REGISTRATION_ATTEMPT).single().to)
        assertTrue(mail.of(MailKind.ALREADY_REGISTERED).isEmpty(), "not the sign-up wording")
        assertTrue(mail.of(MailKind.EMAIL_CHANGE_CODE).isEmpty(), "no code to the owner's address")
        // the open request is the TAKEN one right now (a later request would replace it): its code is unknowable, a guess fails like on a free address
        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).contentType(json).content("""{"code":"000000"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CODE_INVALID"))

        val free = changeEmail(auth, "free@example.com").andExpect(status().isAccepted).andReturn()
        fun shape(r: MvcResult) = JsonPath.read<Map<String, Any?>>(r.response.contentAsString, "$.value").keys
        assertEquals(shape(free), shape(taken))
        assertEquals("free@example.com", mail.of(MailKind.EMAIL_CHANGE_CODE).single().to, "only the free address got a code")
    }

    @Test
    fun `an account that has an address is untouched - the endpoints work and me says no address is needed`() {
        val auth = passwordAccount("plain@example.com")
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.emailRequired").value(false))
    }

    @Test
    fun `an anonymous call keeps its 401 - the filter only speaks to signed-in callers`() {
        mvc.perform(get("/api/v1/things")).andExpect(status().isUnauthorized)
    }

    private fun sessionOf(r: MvcResult) = JsonPath.read<String>(r.response.contentAsString, "$.value.sessionId")

    @Test
    fun `a percent-encoded prefix does not get past the guard - the router reads it as the same path`() {
        val auth = bearer(social("c-noemail-enc"))
        mvc.perform(get(java.net.URI.create("/%61pi/v1/things")).header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(get(java.net.URI.create("/api/v1/%74hings")).header("Authorization", auth)).andExpect(status().isForbidden)
        mvc.perform(get(java.net.URI.create("/api/v1/account/%6De")).header("Authorization", auth)).andExpect(status().isOk)
    }

    @Test
    fun `a functional route and a HEAD call are guarded too, an unmapped path stays a 404 and a preflight stays open`() {
        val auth = bearer(social("c-noemail-fn"))
        mvc.perform(get("/api/v1/fn").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.EMAIL_REQUIRED"))
        mvc.perform(head("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden)
        mvc.perform(head("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/does-not-exist").header("Authorization", auth)).andExpect(status().isNotFound)
        mvc.perform(options("/api/v1/things").header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "GET")).andExpect { assertTrue(it.response.status != 403, "preflight: ${it.response.status}") }
    }

    @Test
    fun `an old session cannot use the first-registration window that the owner's new login opened`() {
        // 공격자의 옛 세션 A (공용 PC · 유출된 리프레시 토큰이 이어 가는 세션) — 열린 지 오래다
        val old = social("c-noemail-stale")
        val oldAuth = bearer(old)
        sessions.age(sessionOf(old), java.time.Duration.ofDays(3))
        // 주인이 다른 기기에서 로그인한다 (세션 B) — 계정의 마지막 로그인이 방금이 된다
        val owner = social("c-noemail-stale")
        mail.sent.clear()

        changeEmail(oldAuth, "attacker@example.com").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.REAUTH_REQUIRED"))
        assertTrue(mail.of(MailKind.EMAIL_CHANGE_CODE).isEmpty(), "no code was mailed")
        changeEmail(bearer(owner), "owner@example.com").andExpect(status().isAccepted)
        assertEquals("owner@example.com", mail.of(MailKind.EMAIL_CHANGE_CODE).single().to)
    }

    @Test
    fun `an unregistered account discards itself at once and its social account can start over - an account with an address cannot`() {
        val first = social("c-noemail-discard")
        val idBefore = JsonPath.read<String>(first.response.contentAsString, "$.value.principal.accountId")

        mvc.perform(post("/api/v1/account/discard").header("Authorization", bearer(first)).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("{}")).andExpect(status().isNoContent)

        mvc.perform(get("/api/v1/account/me").header("Authorization", bearer(first))).andExpect(status().isNotFound)   // the account is gone for good
        val again = social("c-noemail-discard")
        val idAfter = JsonPath.read<String>(again.response.contentAsString, "$.value.principal.accountId")
        assertTrue(idBefore != idAfter, "the Kakao-like identity was free, so a brand-new account was made")

        val registered = passwordAccount("discard-no@example.com")
        mvc.perform(post("/api/v1/account/discard").header("Authorization", registered).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("{}")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.DISCARD_NOT_ALLOWED"))
    }
}
