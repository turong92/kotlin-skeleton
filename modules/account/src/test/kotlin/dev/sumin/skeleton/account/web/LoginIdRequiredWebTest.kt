package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import dev.sumin.skeleton.accounttest.FakeSessionBeans
import dev.sumin.skeleton.accounttest.FakeSocialBeans
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * §14 L6 · L7 · L8 — 아이디 게이트 (아이디도 이메일도 약관도 켠 앱): 아이디 없는 계정은 허용 목록 밖에서 읽기도 `403 ACCOUNT.LOGIN_ID_REQUIRED`,
 * 순서는 아이디 → 이메일, 아이디는 이메일 없이도 정하고 풀리면 막혔던 호출이 그대로 나간다.
 * 이메일 게이트의 `extra-exclude-paths` 가 `GET /api/v1/things` 를 열어 둔다 — 그래도 아이디 게이트는 열리지 않는다(각자 자기 설정만 쓴다).
 */
/** 시험 메서드마다 인스턴스가 새로 생기므로 IP 번호표는 클래스 밖에 둔다 (스프링 컨텍스트의 한도 저장소는 메서드를 넘어 이어진다) */
private val claimIps = java.util.concurrent.atomic.AtomicInteger()

@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.social.sign-up=true",
        "skeleton.account.email.required=true",
        "skeleton.account.email.extra-exclude-paths=GET /api/v1/things",
        "skeleton.account.login-id.enabled=true",
        "skeleton.account.login-id.reserved=admin",
        "skeleton.account.login-id.claim-limit=4",
        "skeleton.auth-social.providers.fakeidp.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class, FakeSocialBeans::class, FakeSessionBeans::class, ForeignModuleBeans::class)
class LoginIdRequiredWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var accounts: dev.sumin.skeleton.account.AccountRepository

    private val json = MediaType.APPLICATION_JSON
    private val required = "ACCOUNT.LOGIN_ID_REQUIRED"
    private fun bearer(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    private fun social(code: String) = mvc.perform(post("/api/v1/auth/social/fakeidp/login").contentType(json).content("""{"authorizationCode":"$code"}""")).andExpect(status().isOk).andReturn()
    private fun uniq() = "id${System.nanoTime()}"

    /** 아이디도 이메일도 없는 소셜 계정 */
    private fun bare(code: String) = bearer(social(code))

    /** 아이디와 이메일이 있는 비밀번호 계정 (가입이 아이디를 받는다) */
    private fun member(loginId: String = uniq()): String {
        val email = "$loginId@example.com"
        mail.sent.clear()
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"$email","password":"tangerine-42-moon","loginId":"$loginId"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        val verified = mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
        return bearer(verified)
    }

    /** 정하기는 IP 별로도 센다(I3) — 시험마다 다른 IP 에서 부르고, 한 IP 를 일부러 쓰는 시험만 [ip] 를 넘긴다 */
    private fun claim(auth: String, loginId: String, ip: String = "198.18.${claimIps.incrementAndGet() / 250}.${claimIps.get() % 250 + 1}"): ResultActions =
        mvc.perform(post("/api/v1/account/login-id").with { it.remoteAddr = ip; it }.header("Authorization", auth).contentType(json).content("""{"loginId":"$loginId"}"""))
    private fun ResultActions.blockedBy(code: String) = andExpect(status().isForbidden).andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.data").doesNotExist())

    @Test
    fun `L7 - an account without an id is stopped outside the allow list on reads too, with no data`() {
        val auth = bare("c-noemail-lid1")
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value(required)).andExpect(jsonPath("$.data").doesNotExist())
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(required)
        mvc.perform(head("/api/v1/things").header("Authorization", auth)).andExpect(status().isForbidden)
        mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).blockedBy(required)
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).blockedBy(required)
        mvc.perform(delete("/api/v1/account/identities/idn_x").header("Authorization", auth)).blockedBy(required)
    }

    @Test
    fun `L7 - the allow list stays open - me, the claim and its check, the email flow, sign-out and the auth endpoints, deletion`() {
        val auth = bare("c-noemail-lid2")
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(post("/api/v1/account/login-id/availability").header("Authorization", auth).contentType(json).content("""{"loginId":"somebody.new"}""")).andExpect(status().isOk)
        mvc.perform(get("/api/v1/auth/me").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/auth/methods")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/account/delete/confirmation").header("Authorization", auth)).andExpect(status().isAccepted)
        mvc.perform(post("/api/v1/account/email/change").header("Authorization", auth).header("Idempotency-Key", java.util.UUID.randomUUID().toString()).contentType(json).content("""{"newEmail":"first-${uniq()}@example.com"}""")).andExpect(status().isAccepted)
    }

    @Test
    fun `L7 - the order is login id then email - an account with neither is told about the id first`() {
        val auth = bare("c-noemail-lid3")
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(required)
        claim(auth, uniq()).andExpect(status().isOk)
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy("ACCOUNT.EMAIL_REQUIRED")
    }

    @Test
    fun `L7 - the email gate's own extra paths do not open the id gate`() {
        val auth = bare("c-noemail-lid4")
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).blockedBy(required)   // the email gate would let GET /things pass
        claim(auth, uniq()).andExpect(status().isOk)
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)   // now only the email gate is left and its extra path opens it
    }

    @Test
    fun `L7 - an account with an id is untouched - and one with an id and an address too`() {
        val auth = member()
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
    }

    @Test
    fun `L7 - an anonymous call keeps its 401 and a preflight passes`() {
        mvc.perform(get("/api/v1/things")).andExpect(status().isUnauthorized)
        mvc.perform(options("/api/v1/things").header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "POST")).andExpect { assertTrue(it.response.status != 403, "a preflight is not gated") }
    }

    @Test
    fun `L7 - a percent-encoded prefix does not get past the guard`() {
        val auth = bare("c-noemail-lid5")
        mvc.perform(get(java.net.URI.create("/%61pi/v1/things")).header("Authorization", auth)).blockedBy(required)
        mvc.perform(get(java.net.URI.create("/api/v1/%74hings")).header("Authorization", auth)).blockedBy(required)
        mvc.perform(get(java.net.URI.create("/api//v1/things")).header("Authorization", auth)).andExpect { assertTrue(it.response.status in 400..403, "a double slash never reaches a handler (the unit test pins the interceptor's own verdict): ${it.response.status}") }
        mvc.perform(get(java.net.URI.create("/api/v1/account/%6De")).header("Authorization", auth)).andExpect(status().isOk)
    }

    @Test
    fun `L6 - me carries loginId as null until it is chosen, then the string - and PATCH me carries it too`() {
        val auth = bare("c-noemail-lid6")
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.loginId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect { assertTrue("loginId" in JsonPath.read<Map<String, Any?>>(it.response.contentAsString, "$.value"), "the key is there with a null value") }
        claim(auth, "Gate.User1").andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.loginId").value("gate.user1"))
        val member = member("patch.me1")
        mvc.perform(patch("/api/v1/account/me").header("Authorization", member).contentType(json).content("""{"locale":"ko"}""")).andExpect(status().isOk).andExpect(jsonPath("$.value.loginId").value("patch.me1"))
    }

    @Test
    fun `L8 - choosing an id answers the same body as me, needs no address, and the blocked call can go out again`() {
        val auth = bare("c-noemail-lid7")
        mvc.perform(post("/api/v1/things").header("Authorization", auth)).blockedBy(required)
        claim(auth, "  New.Gate-Id ").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.loginId").value("new.gate-id"))
            .andExpect(jsonPath("$.value.email").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.value.emailRequired").value(true))
            .andExpect(jsonPath("$.value.id").isNotEmpty)
        mvc.perform(get("/api/v1/things").header("Authorization", auth)).andExpect(status().isOk)
    }

    @Test
    fun `L8 - 400 on the loginId field, 409 TAKEN for a live, reserved or other's id, 409 ALREADY_SET for a second try, 401 anonymous`() {
        val taken = uniq(); member(taken)
        val auth = bare("c-noemail-lid8")
        listOf("" to "Required", "abc" to "Size", "a..bcd" to "Pattern").forEach { (value, code) ->
            claim(auth, value).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("loginId")).andExpect(jsonPath("$.errors[0].code").value(code))
        }
        claim(auth, taken.uppercase()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_TAKEN"))
        claim(auth, "admin").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_TAKEN"))
        claim(auth, uniq()).andExpect(status().isOk)
        claim(auth, uniq()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_ALREADY_SET"))
        mvc.perform(post("/api/v1/account/login-id").contentType(json).content("""{"loginId":"${uniq()}"}""")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `L8 - past claim-limit attempts it is 429 RATE_LIMITED with Retry-After, failures counted, and the account stays without an id`() {
        val taken = uniq(); member(taken)
        val auth = bare("c-noemail-lid9")
        repeat(4) { claim(auth, taken, "198.19.0.1").andExpect(status().isConflict) }
        claim(auth, uniq(), "198.19.0.1").andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("ACCOUNT.RATE_LIMITED")).andExpect(header().exists("Retry-After"))
        mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(jsonPath("$.value.loginId").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `I3 - one IP claiming for ever new accounts is stopped at claim-limit - 429 RATE_LIMITED, and the refused id stays free`() {
        val ip = "198.19.0.2"
        val accountsOnOneIp = (1..5).map { bare("c-noemail-ip$it") }
        val ids = accountsOnOneIp.map { uniq() }
        accountsOnOneIp.take(4).forEachIndexed { i, auth -> claim(auth, ids[i], ip).andExpect(status().isOk) }
        claim(accountsOnOneIp[4], ids[4], ip).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("ACCOUNT.RATE_LIMITED")).andExpect(header().exists("Retry-After"))
        claim(accountsOnOneIp[4], ids[4], "198.19.0.3").andExpect(status().isOk)   // another IP: the refused id was never burnt
    }

    @Test
    fun `L8 - an account that stopped being active gets 403 NOT_ACTIVE - not a TAKEN and not a 500`() {
        val auth = bare("c-noemail-lid10")
        val id = JsonPath.read<String>(mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andReturn().response.contentAsString, "$.value.id")
        accounts.update(id, dev.sumin.skeleton.account.AccountPatch(status = dev.sumin.skeleton.account.AccountStatus.SUSPENDED), java.time.Instant.now())
        claim(auth, uniq()).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCOUNT.NOT_ACTIVE"))
    }

    @Test
    fun `the request body never prints the id`() {
        assertTrue("secret.one" !in LoginIdRequest("secret.one").toString())
    }
}
