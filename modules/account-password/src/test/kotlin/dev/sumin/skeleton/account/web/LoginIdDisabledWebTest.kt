package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * §16 「꺼짐 · 기본 = 지금과 같다」 ② — 아이디 기능이 꺼진(기본) 앱: `methods` · `me` 에 `loginId` 키가 없다, 가입 본문의 `loginId` 는 읽지 않는다(계정은 만들어지고 열은 NULL),
 * `username` 은 이메일로 읽힌다, 새 경로는 컨트롤러가 없어 다른 모르는 경로와 같다(로그인한 호출은 404, 익명은 그 경로가 받는 그대로).
 * 관리자 API 는 켠 채로 둔다(관리자 응답에 키가 없고 `?loginId=` 는 읽지 않는다). 찾기 경로(`POST /account/login-id/forgot`)도 컨트롤러가 없다.
 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.admin.enabled=true",
        "skeleton.account.bootstrap.admin-email=off-boss@example.com",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class LoginIdDisabledWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var accounts: AccountRepository
    private val json = MediaType.APPLICATION_JSON
    private val nextIp = java.util.concurrent.atomic.AtomicInteger()   // 가입 한도는 주소별 — 시험이 늘어도 한 주소로 몰리지 않게

    private fun signUpAndVerify(email: String, extra: String = ""): MvcResult {
        val signUp = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${1 + nextIp.getAndIncrement() % 200}"; it }.contentType(json).content("""{"email":"$email","password":"tangerine-42-moon"$extra}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        return mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}"}""")).andExpect(status().isOk).andReturn()
    }

    private fun auth(r: MvcResult) = "Bearer " + JsonPath.read<String>(r.response.contentAsString, "$.value.accessToken")
    private fun keys(body: String, path: String) = JsonPath.read<Map<String, Any?>>(body, path).keys

    @Test
    fun `methods and me have no loginId key - the key is absent, not null`() {
        val auth = auth(signUpAndVerify("off-keys@example.com"))
        val methods = mvc.perform(get("/api/v1/auth/methods")).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse("loginId" in keys(methods, "$.value"), methods)
        val me = mvc.perform(get("/api/v1/account/me").header("Authorization", auth)).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse("loginId" in keys(me, "$.value"), me)
        val patched = mvc.perform(patch("/api/v1/account/me").header("Authorization", auth).contentType(json).content("""{"locale":"ko"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse("loginId" in keys(patched, "$.value"), patched)
        assertFalse("loginId" in me, "not even as a nested key")
    }

    @Test
    fun `a loginId in the sign-up body is ignored - the account is made and the column stays NULL, even a malformed or taken-looking value`() {
        listOf("\"someone.new\"", "\"x\"", "\"bad id!!\"", "null").forEachIndexed { n, value ->
            val email = "off-signup$n@example.com"
            signUpAndVerify(email, ""","loginId":$value""")
            val account = accounts.findByEmail(email)
            assertTrue(account != null, "the account was created")
            assertNull(account.loginId)
        }
        assertNull(accounts.findByLoginId("someone.new"))
    }

    @Test
    fun `a loginId in the verify body is ignored too`() {
        val signUp = mvc.perform(post("/api/v1/account/sign-up").contentType(json).content("""{"email":"off-verify@example.com","password":"tangerine-42-moon"}""")).andExpect(status().isAccepted).andReturn()
        val id = JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")
        mvc.perform(post("/api/v1/auth/verify-email").contentType(json).content("""{"signUpId":"$id","code":"${mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")}","loginId":"ignored.one"}""")).andExpect(status().isOk)
        assertNull(accounts.findByEmail("off-verify@example.com")!!.loginId)
    }

    @Test
    fun `username is read as the email - a plain word is not an id, the email still signs in`() {
        signUpAndVerify("off-login@example.com")
        mvc.perform(post("/api/v1/auth/login").contentType(json).content("""{"username":"off-login@example.com","password":"tangerine-42-moon"}""")).andExpect(status().isOk)
        mvc.perform(post("/api/v1/auth/login").contentType(json).content("""{"username":"off-login","password":"tangerine-42-moon"}""")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `off - the claim and the availability paths are 404 for a signed-in caller and what any unknown path gets for an anonymous one (nothing is opened)`() {
        val auth = auth(signUpAndVerify("off-paths@example.com"))
        listOf("/api/v1/account/login-id", "/api/v1/account/login-id/availability", "/api/v1/account/login-id/forgot").forEach { path ->
            mvc.perform(post(path).header("Authorization", auth).contentType(json).content("""{"loginId":"someone"}""")).andExpect(status().isNotFound)
            val unknown = mvc.perform(post("$path-nothing").contentType(json).content("{}")).andReturn().response.status
            mvc.perform(post(path).contentType(json).content("""{"loginId":"someone"}""")).andExpect(status().`is`(unknown))
        }
    }

    @Test
    fun `off - the reminder path sends nothing, for a known address too`() {
        signUpAndVerify("off-forgot@example.com")
        mail.sent.clear()
        mvc.perform(post("/api/v1/account/login-id/forgot").contentType(json).content("""{"email":"off-forgot@example.com"}""")).andExpect(status().`is`(
            mvc.perform(post("/api/v1/account/login-id/forgot-nothing").contentType(json).content("{}")).andReturn().response.status,
        ))
        assertTrue(mail.of(MailKind.LOGIN_ID_REMINDER).isEmpty())
    }

    @Test
    fun `off - a signed-in account is never gated, it just has no gate registered`() {
        val auth = auth(signUpAndVerify("off-gate@example.com"))
        // accounts made before the feature was on have no id; off, nothing asks for one
        mvc.perform(get("/api/v1/account/identities").header("Authorization", auth)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/things-nothing").header("Authorization", auth)).andExpect(status().isNotFound)   // an unknown path is a 404, never a 403 LOGIN_ID_REQUIRED
    }

    @Test
    fun `off - the admin list and the one-account read have no loginId key, and ?loginId= is not read (a filter on it would find nothing)`() {
        val boss = auth(signUpAndVerify("off-boss@example.com"))
        val other = signUpAndVerify("off-admin-target@example.com")
        val id = JsonPath.read<String>(other.response.contentAsString, "$.value.principal.accountId")
        val listed = mvc.perform(get("/api/v1/admin/accounts").param("email", "off-admin-target").header("Authorization", boss)).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse("loginId" in keys(listed, "$.values[0]"), listed)
        val one = mvc.perform(get("/api/v1/admin/accounts/$id").header("Authorization", boss)).andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse("loginId" in keys(one, "$.value"), one)
        val ignored = mvc.perform(get("/api/v1/admin/accounts").param("email", "off-admin-target").param("loginId", "no-such-id").header("Authorization", boss)).andExpect(status().isOk).andReturn().response.contentAsString
        assertTrue(JsonPath.read<List<Any>>(ignored, "$.values").size == 1, "the query is ignored, not applied: $ignored")
    }
}
