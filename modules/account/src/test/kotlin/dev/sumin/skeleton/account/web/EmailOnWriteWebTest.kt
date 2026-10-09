package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.accounttest.FakeSessionBeans
import dev.sumin.skeleton.accounttest.FakeSocialBeans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 다른 모듈의 변경 엔드포인트를 흉내 낸다 — 앱이 yml 로 연 경로 둘, 열지 않은 결제 */
@RestController
class OnWriteProbeController {
    @PatchMapping("/api/v1/probe/read") fun readPatch() = mapOf("ok" to true)
    @PostMapping("/api/v1/probe/read") fun readPost() = mapOf("ok" to true)
    @PostMapping("/api/v1/probe/peek") fun peekPost() = mapOf("ok" to true)
    @PatchMapping("/api/v1/probe/peek") fun peekPatch() = mapOf("ok" to true)
    @PostMapping("/api/v1/payments/orders") fun order() = mapOf("ok" to true)
}

@TestConfiguration(proxyBeanMethods = false)
class OnWriteProbeBeans {
    @Bean fun onWriteProbeController() = OnWriteProbeController()
}

/**
 * §14 E7~E11 — `email.required=true` + `required-when=WRITE` (아이디도 켠 견본 앱 모양): 주소 없는 계정은 읽기 전부와 허용 목록의 변경만, 나머지 변경은 `403 ACCOUNT.EMAIL_REQUIRED`.
 * `me` 는 `emailRequired:false` + `emailRequiredToWrite`, 첫 등록의 재인증 규칙은 그대로, 버리기는 되고, 아이디 게이트가 앞선다.
 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
        "skeleton.account.email.required=true",
        "skeleton.account.email.required-when=WRITE",
        "skeleton.account.email.extra-exclude-paths=PATCH /api/v1/probe/read, POST /api/v1/probe/peek",
        "skeleton.account.login-id.enabled=true",
        "skeleton.account.login-id.claim-limit=20", // the per-IP claim bucket: this class claims an id for most of its accounts
        "skeleton.auth-social.providers.fakeidp.enabled=true",
        "skeleton.auth-social.providers.fakeidp2.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, FakeSocialBeans::class, FakeSessionBeans::class, ForeignModuleBeans::class, OnWriteProbeBeans::class)
class EmailOnWriteWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var sessions: dev.sumin.skeleton.accounttest.FakeSessionBook

    private val json = MediaType.APPLICATION_JSON
    private val emailRequired = "ACCOUNT.EMAIL_REQUIRED"
    private val idRequired = "ACCOUNT.LOGIN_ID_REQUIRED"
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    private fun social(code: String) = mvc.perform(post("/api/v1/auth/social/fakeidp/login").contentType(json).content("""{"authorizationCode":"$code"}""")).andExpect(status().isOk).andReturn()
    private fun uniq() = "id${System.nanoTime()}"
    private fun claim(auth: String, loginId: String = uniq()) = mvc.perform(post("/api/v1/account/login-id").header("Authorization", auth).contentType(json).content("""{"loginId":"$loginId"}""")).andExpect(status().isOk)

    /** 아이디는 정했고 주소는 없는 소셜 계정 */
    private fun noAddress(code: String): String = bearer(social(code)).also { claim(it) }

    private fun withAddress(): String {
        val loginId = uniq()
        mail.sent.clear()
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"$loginId@example.com","password":"tangerine-42-moon","loginId":"$loginId"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val verified = mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
        return bearer(verified)
    }

    private fun ResultActions.blockedBy(code: String) = andExpect(status().isForbidden).andExpect(jsonPath("$.code").value(code))
    private fun changeEmail(auth: String, to: String) = mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("""{"newEmail":"$to"}"""))
    private fun discard(auth: String) = mvc.perform(post("/api/v1/account/discard").header("Authorization", auth).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("{}"))

    @Test
    fun `E8 - reads pass for an account without an address, writes outside the allow list are 403 with the same code and body`() {
        val auth = noAddress("c-noemail-ow1")
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/fn").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(head("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(options("/api/v1/things").header("Authorization", auth)).andExpect { assertTrue(it.response.status != 403, "OPTIONS reads: ${it.response.status}") }

        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(emailRequired)
            .andExpect(jsonPath("$.title").value("Register an email address first")).andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()))
        mvc.perform(patch("/api/v1/probe/peek").header("Authorization", auth)).blockedBy(emailRequired)   // PUT / TRACE etc.: the unit test's table (a method without a handler is a 405 before any interceptor)
    }

    @Test
    fun `an account without an address links and unlinks social sign-in methods, but still cannot create a password`() {
        val auth = noAddress("c-noemail-link1")
        val own = """{"provider":"fakeidp","authorizationCode":"c-noemail-link1"}"""
        mvc.perform(post("/api/v1/account/identities/social/fakeidp2").header("Authorization", auth).contentType(json).content("""{"authorizationCode":"c-noemail-link2"}"""))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.REAUTH_REQUIRED")) // the gate is open, the re-authentication still applies
        mvc.perform(post("/api/v1/account/identities/social/fakeidp2").header("Authorization", auth).contentType(json).content("""{"authorizationCode":"c-noemail-link2","socialReauth":$own}"""))
            .andExpect(status().isCreated)
        val listed = mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isOk).andReturn()
        assertEquals(2, JsonPath.read<List<Any>>(listed.response.contentAsString, "$.values").size)
        val linkedId = JsonPath.read<List<String>>(listed.response.contentAsString, "$.values[?(@.method=='fakeidp2')].id").single()
        mvc.perform(delete("/api/v1/account/identities/$linkedId").header("Authorization", auth).contentType(json).content("""{"socialReauth":$own}"""))
            .andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/account/password/change").header("Authorization", auth).contentType(json).content("{}")).blockedBy(emailRequired)
    }

    @Test
    fun `E9 - PATCH me is open, the app's extra paths open their own method only, the fixed list stays open`() {
        val auth = noAddress("c-noemail-ow2")
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.locale").value("ko"))
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"timeZone":"Asia/Seoul"}""")).andExpect(status().isOk)

        // extra-exclude-paths: "PATCH /probe/read" opens that method on that path and nothing else
        mvc.perform(patch("/api/v1/probe/read").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(post("/api/v1/probe/peek").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(post("/api/v1/probe/read").header("Authorization", auth)).blockedBy(emailRequired)
        mvc.perform(patch("/api/v1/probe/peek").header("Authorization", auth)).blockedBy(emailRequired)

        mvc.perform(get("/api/v1/auth/me").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(post("/api/v1/account/delete/confirmation").header("Authorization", auth)).andExpect(status().isAccepted)
        mvc.perform(post("/api/v1/account/password/change").header("Authorization", auth).contentType(json).content("{}")).blockedBy(emailRequired)   // other account changes: the address comes first
        mvc.perform(post("/api/v1/account/reauth/confirmation").header("Authorization", auth)).blockedBy(emailRequired)
        mvc.perform(post("/api/v1/payments/orders").header("Authorization", auth)).blockedBy(emailRequired)   // payments are not opened
    }

    @Test
    fun `E7 - me has emailRequired false and emailRequiredToWrite true while there is no address, and false once there is`() {
        val auth = noAddress("c-noemail-ow3")
        val me = mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.emailRequired").value(false))
            .andExpect(jsonPath("$.value.emailRequiredToWrite").value(true)).andReturn()
        val keys = JsonPath.read<Map<String, Any?>>(me.response.contentAsString, "$.value").keys
        assertTrue("emailRequired" in keys && "emailRequiredToWrite" in keys, "$keys")
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"en"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.emailRequired").value(false)).andExpect(jsonPath("$.value.emailRequiredToWrite").value(true))

        val registered = withAddress()
        mvc.perform(get("/api/v1/account/me").header("Authorization", registered)).andExpect(jsonPath("$.value.emailRequired").value(false)).andExpect(jsonPath("$.value.emailRequiredToWrite").value(false))
        mvc.perform(post("/api/v1/things").header("Authorization", registered)).andExpect(status().isOk)
    }

    @Test
    fun `once the first address is confirmed every write passes and me flips`() {
        val auth = noAddress("c-noemail-ow4")
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(emailRequired)
        mail.sent.clear()
        changeEmail(auth, "first-${uniq()}@example.com").andExpect(status().isAccepted)
        val code = mail.of(MailKind.EMAIL_CHANGE_CODE).single().vars.getValue("code")
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(emailRequired)   // not before the code
        mvc.perform(post("/api/v1/account/email/change/confirm").header("Authorization", auth).contentType(json).content("""{"code":"$code"}""")).andExpect(status().isNoContent)
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.emailRequiredToWrite").value(false))
    }

    @Test
    fun `E10 - the first-registration rule is the same - a session older than the window needs the provider again`() {
        val old = social("c-noemail-ow5")
        val oldAuth = bearer(old).also { claim(it) }
        sessions.age(JsonPath.read<String>(old.response.contentAsString, "$.value.sessionId"), java.time.Duration.ofDays(3))
        val owner = social("c-noemail-ow5")   // the owner's new login must not open the old session's window
        mail.sent.clear()
        changeEmail(oldAuth, "late@example.com").andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.REAUTH_REQUIRED"))
        assertTrue(mail.of(MailKind.EMAIL_CHANGE_CODE).isEmpty(), "no code was mailed")
        changeEmail(bearer(owner), "fresh@example.com").andExpect(status().isAccepted)
    }

    @Test
    fun `E11 - an account without an address discards itself - before and after choosing an id - and the id is gone for good`() {
        val before = bearer(social("c-noemail-ow6"))
        discard(before).andExpect(status().isNoContent)   // the id gate lets the discard path through even with no id yet
        mvc.perform(get("/api/v1/account/me").header("Authorization", before)).andExpect(status().isNotFound)

        val chosen = uniq()
        val after = bearer(social("c-noemail-ow7")); claim(after, chosen)
        mvc.perform(post("/api/v1/account/login-id/availability").contentType(json).content("""{"loginId":"$chosen"}""")).andExpect(jsonPath("$.value.available").value(false))
        discard(after).andExpect(status().isNoContent)

        mvc.perform(post("/api/v1/account/login-id/availability").contentType(json).content("""{"loginId":"$chosen"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.available").value(false)).andExpect(jsonPath("$.value.reason").value("TAKEN"))
        mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"again-${uniq()}@example.com","password":"tangerine-42-moon","loginId":"$chosen"}""")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_TAKEN"))
        val newcomer = bearer(social("c-noemail-ow8"))
        mvc.perform(post("/api/v1/account/login-id").header("Authorization", newcomer).contentType(json).content("""{"loginId":"$chosen"}""")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_TAKEN"))
    }

    @Test
    fun `an account with an address cannot discard - the same 409 in this mode`() {
        discard(withAddress()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.DISCARD_NOT_ALLOWED"))
    }

    @Test
    fun `the order - an account with no id is told about the id first, even for a write and even with no address`() {
        val auth = bearer(social("c-noemail-ow9"))
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(idRequired)
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).blockedBy(idRequired)   // the id gate blocks reads too
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).blockedBy(idRequired)   // PATCH me is open for the email gate only
        claim(auth)
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(emailRequired)
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
    }

    @Test
    fun `a method override cannot turn a write into a read - the gate sees the method that runs`() {
        val auth = noAddress("c-noemail-ow10")
        mvc.perform(post("/api/v1/things").header("Authorization", auth).header("X-HTTP-Method-Override", "GET")).blockedBy(emailRequired)
        mvc.perform(post("/api/v1/things").header("Authorization", auth).header("X-HTTP-Method-Override", "HEAD")).blockedBy(emailRequired)
        mvc.perform(post("/api/v1/things").header("Authorization", auth).param("_method", "GET")).blockedBy(emailRequired)
        mvc.perform(post("/api/v1/things").header("Authorization", auth).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("_method=GET")).blockedBy(emailRequired)
        mvc.perform(post("/api/v1/things").header("Authorization", auth).header("X-HTTP-Method-Override", "PATCH").header("X-Method-Override", "GET")).blockedBy(emailRequired)
    }

    @Test
    fun `an anonymous call keeps its 401, a percent-encoded prefix does not help a write`() {
        mvc.perform(post("/api/v1/things")).andExpect(status().isUnauthorized)
        val auth = noAddress("c-noemail-ow11")
        mvc.perform(post(java.net.URI.create("/%61pi/v1/things")).header("Authorization", auth)).blockedBy(emailRequired)
        mvc.perform(patch(java.net.URI.create("/api/v1/account/%6De")).header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).andExpect(status().isOk)
        assertEquals(404, mvc.perform(get("/api/v1/does-not-exist").header("Authorization", auth)).andReturn().response.status, "an unmapped read stays a 404")
    }
}
