package dev.sumin.skeleton.account.web

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.accounttest.AccountTestBeans
import dev.sumin.skeleton.accounttest.AccountWebTestApplication
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val loginIdIps = AtomicInteger()

/** §14 L2 · L3 · L4 · L5 — 가입 요청과 확정 요청의 `loginId` (상태 · 본문). 아이디 기능이 켜진 앱 */
@SpringBootTest(
    classes = [AccountWebTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.account.login-id.enabled=true",
        "skeleton.account.login-id.reserved=admin",
    ],
)
@AutoConfigureMockMvc
@Import(AccountTestBeans::class)
class LoginIdWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mail: RecordingMailer
    @Autowired lateinit var accounts: dev.sumin.skeleton.account.AccountRepository
    @Autowired lateinit var core: dev.sumin.skeleton.account.AccountCore

    @BeforeEach fun clear() { mail.sent.clear() }

    private fun unique() = "u${System.nanoTime()}@example.com"
    private fun id() = "id${System.nanoTime()}"

    private fun signUp(email: String, loginIdJson: String?): ResultActions =
        mvc.perform(
            post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${1 + loginIdIps.getAndIncrement() % 200}"; it }.contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"tangerine-42-moon"${loginIdJson?.let { ""","loginId":$it""" } ?: ""}}"""),
        )

    private fun verify(signUpId: String, code: String, loginIdJson: String? = null): ResultActions =
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$signUpId","code":"$code"${loginIdJson?.let { ""","loginId":$it""" } ?: ""}}"""))

    private fun signUpId(r: ResultActions) = JsonPath.read<String>(r.andReturn().response.contentAsString, "$.value.signUpId")
    private fun lastCode() = mail.of(MailKind.VERIFY_CODE).last().vars.getValue("code")

    private fun register(loginId: String) {
        val r = signUp(unique(), "\"$loginId\"").andExpect(status().isAccepted)
        verify(signUpId(r), lastCode()).andExpect(status().isOk)
    }

    @Test
    fun `L2 - a missing, short or malformed loginId is a 400 on the loginId field with Required, Size or Pattern`() {
        listOf(null to "Required", "\"\"" to "Required", "\"abc\"" to "Size", "\"a..bcd\"" to "Pattern", "\"bad id\"" to "Pattern", "\"12345\"" to "Pattern").forEach { (json, code) ->
            signUp(unique(), json).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("COMMON.VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("loginId"))
                .andExpect(jsonPath("$.errors[0].code").value(code))
        }
    }

    @Test
    fun `L2 - a taken, reserved or erased-looking id is the 409 LOGIN_ID_TAKEN, the same for a new and a registered email`() {
        val taken = id()
        register(taken)
        listOf("\"$taken\"", "\"${taken.uppercase()}\"", "\"ad_min\"").forEach { json ->
            signUp(unique(), json).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_TAKEN"))
        }
    }

    @Test
    fun `L2 - a free id is the usual 202 and its body carries no loginId`() {
        val r = signUp(unique(), "\"${id()}\"").andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("VERIFICATION_SENT")).andReturn()
        assertTrue("loginId" !in r.response.contentAsString)
    }

    @Test
    fun `L3 - an id taken before the confirmation is a 409 that keeps the attempt, and the same signUpId and code finish with another loginId`() {
        val contested = id()
        val first = signUp(unique(), "\"$contested\"").andExpect(status().isAccepted)
        val firstId = signUpId(first); val firstCode = lastCode()
        register(contested)   // someone else confirms the same id first
        verify(firstId, firstCode).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ACCOUNT.LOGIN_ID_TAKEN"))
        verify(firstId, firstCode, "\"${id()}\"").andExpect(status().isOk).andExpect(jsonPath("$.value.accessToken").isString)
    }

    @Test
    fun `L3 - a malformed replacement loginId is a 400 on loginId and does not spend the attempt`() {
        val first = signUp(unique(), "\"${id()}\"").andExpect(status().isAccepted)
        val sid = signUpId(first); val code = lastCode()
        verify(sid, code, "\"x\"").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("loginId"))
        verify(sid, code).andExpect(status().isOk)
    }

    @Test
    fun `the candidate login id stays out of toString of the request bodies and the command`() {
        val secret = "S3cr3t-LoginId"
        listOf<Any>(
            SignUpRequest("a@b.co", "pw", loginId = secret),
            VerifyEmailRequest("sid", "123456", loginId = secret),
            dev.sumin.skeleton.account.SignUpCommand("a@b.co", "pw", null, null, null, null, null, loginId = secret),
        ).forEach { assertTrue(secret !in it.toString(), "${it.javaClass.simpleName} leaked the login id") }
    }

    private fun check(loginIdJson: String, ip: String): ResultActions =
        mvc.perform(post("/api/v1/account/login-id/availability").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"loginId":$loginIdJson}"""))

    @Test
    fun `L1 - methods carries loginId with the configured length bounds when the feature is on`() {
        mvc.perform(get("/api/v1/auth/methods")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.loginId.minLength").value(4)).andExpect(jsonPath("$.value.loginId.maxLength").value(20))
    }

    @Test
    fun `L4 - public, always 200 with normalized + available, no-store`() {
        check("\"Sumin.K${System.nanoTime() % 100000}\"", "203.0.113.10").andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.value.normalized").isString).andExpect(jsonPath("$.value.available").value(true))
            .andExpect(jsonPath("$.value.reason").doesNotExist()).andExpect(jsonPath("$.value.code").doesNotExist())
    }

    @Test
    fun `M4 - the keys reason and code are absent from the JSON when they do not apply - not null`() {
        fun keys(json: String, ip: String) = JsonPath.read<Map<String, Any?>>(check(json, ip).andExpect(status().isOk).andReturn().response.contentAsString, "$.value").keys
        assertEquals(setOf("normalized", "available"), keys("\"free${System.nanoTime() % 100000}x\"", "203.0.113.15"))
        assertEquals(setOf("normalized", "available", "reason"), keys("\"admin\"", "203.0.113.15"))
        assertEquals(setOf("normalized", "available", "reason", "code"), keys("\"Su\"", "203.0.113.15"))
    }

    @Test
    fun `L4 - TAKEN covers a live id and a reserved id with the same body shape`() {
        val taken = id()
        register(taken)
        listOf(taken.uppercase() to taken, "ad_min" to "ad_min").forEach { (raw, normalized) ->
            check("\"$raw\"", "203.0.113.11").andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value.normalized").value(normalized))
                .andExpect(jsonPath("$.value.available").value(false)).andExpect(jsonPath("$.value.reason").value("TAKEN")).andExpect(jsonPath("$.value.code").doesNotExist())
        }
    }

    @Test
    fun `L4 - INVALID is still 200 and names the field code`() {
        listOf("\"\"" to "Required", "\"Su\"" to "Size", "\"a..bcd\"" to "Pattern").forEach { (json, code) ->
            check(json, "203.0.113.12").andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.value.available").value(false)).andExpect(jsonPath("$.value.reason").value("INVALID")).andExpect(jsonPath("$.value.code").value(code))
        }
    }

    @Test
    fun `L4 - the 31st call from one IP is 429 ACCOUNT_RATE_LIMITED with Retry-After, form errors do not count, another IP is fine`() {
        val ip = "203.0.113.13"
        repeat(10) { check("\"x\"", ip).andExpect(status().isOk) }   // INVALID: free
        repeat(30) { check("\"free${System.nanoTime()}\"", ip).andExpect(status().isOk) }
        check("\"free${System.nanoTime()}\"", ip).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("ACCOUNT.RATE_LIMITED")).andExpect(header().exists("Retry-After"))
        check("\"free${System.nanoTime()}\"", "203.0.113.14").andExpect(status().isOk)
    }

    @Test
    fun `the candidate login id stays out of toString of the availability request`() {
        assertTrue("S3cr3t-LoginId" !in LoginIdAvailabilityRequest("S3cr3t-LoginId").toString())
    }

    // ---- L5 — POST /auth/login 의 username 이 아이디

    private fun login(json: String, ip: String): org.springframework.test.web.servlet.MvcResult =
        mvc.perform(post("/api/v1/auth/login").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn()

    private fun member(): Pair<String, String> {
        val email = unique(); val loginId = id()
        val r = signUp(email, "\"$loginId\"").andExpect(status().isAccepted)
        verify(signUpId(r), lastCode()).andExpect(status().isOk)
        return email to loginId
    }

    @Test
    fun `L5 - the id signs in in any letter case, a wrong password and an unknown id and a social-only id are the same 401 to the letter, and the token carries no id`() {
        val (email, loginId) = member()
        val ok = login("""{"username":"${loginId.uppercase()}","password":"tangerine-42-moon"}""", "203.0.113.20")
        assertEquals(200, ok.response.status)
        val body = ok.response.contentAsString
        assertTrue(loginId !in body && loginId.uppercase() !in body, "the response does not echo the id")
        val token = JsonPath.read<String>(body, "$.value.accessToken")
        val payload = String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1]))
        assertTrue(loginId !in payload, "no id in the JWT")

        val now = java.time.Instant.now()
        val social = "soc${System.nanoTime()}"
        val socialId = "acc_$social"
        accounts.insert(dev.sumin.skeleton.account.Account(socialId, "$social@example.com", true, dev.sumin.skeleton.account.AccountStatus.ACTIVE, setOf("USER"), null, "ko", "Asia/Seoul", now, now, loginId = social), listOf(dev.sumin.skeleton.account.Identity("idn_$social", socialId, "google", "g-$social", true, createdAt = now)), core.loginIdHashOf(social))

        val bodies = listOf(
            """{"username":"$loginId","password":"wrong-password-99"}""",
            """{"username":"no.such.${System.nanoTime()}","password":"tangerine-42-moon"}""",
            """{"username":"$social","password":"tangerine-42-moon"}""",
            """{"email":"$email","password":"wrong-password-99"}""",
        ).mapIndexed { i, json -> login(json, "203.0.113.${30 + i}") }
        bodies.forEach { assertEquals(401, it.response.status) }
        // traceId · spanId · timestamp 는 요청마다 다르다 — 나머지는 글자까지 같아야 한다
        val texts = bodies.map { it.response.contentAsString.replace(Regex(""""(traceId|spanId|timestamp)":"[^"]*""""), "") }
        assertEquals(1, texts.toSet().size, "the same body to the letter: $texts")
        assertEquals("AUTH.INVALID_CREDENTIALS", JsonPath.read<String>(texts.first(), "$.code"))
    }

    @Test
    fun `L5 - the id has its own allowance apart from the email's, and an unknown id is counted too (429) (I2)`() {
        val (email, loginId) = member()
        repeat(10) { assertEquals(401, login("""{"username":"$loginId","password":"wrong-password-99"}""", "203.0.113.${60 + it}").response.status) }
        val r = login("""{"username":"${loginId.uppercase()}","password":"tangerine-42-moon"}""", "203.0.113.99")
        assertEquals(429, r.response.status)
        assertEquals("AUTH.TOO_MANY_ATTEMPTS", JsonPath.read<String>(r.response.contentAsString, "$.code"))
        assertEquals(401, login("""{"email":"$email","password":"wrong-password-99"}""", "203.0.113.98").response.status, "the email bucket is untouched")

        val ghost = "ghost.${System.nanoTime()}"
        repeat(10) { assertEquals(401, login("""{"username":"$ghost","password":"x-password-1234"}""", "203.0.114.${1 + it}").response.status) }
        assertEquals(429, login("""{"username":"$ghost","password":"x-password-1234"}""", "203.0.114.99").response.status)
    }

    @Test
    fun `L5 - with the email's allowance spent, the account's own id and an unknown id get the same 401 - the throttle does not confirm which id belongs to which email (I2)`() {
        val (email, loginId) = member()
        repeat(10) { assertEquals(401, login("""{"email":"$email","password":"wrong-password-99"}""", "203.0.115.${1 + it}").response.status) }
        assertEquals(429, login("""{"email":"$email","password":"wrong-password-99"}""", "203.0.115.90").response.status)
        val own = login("""{"username":"$loginId","password":"wrong-password-99"}""", "203.0.115.91")
        val other = login("""{"username":"no.such.${System.nanoTime()}","password":"wrong-password-99"}""", "203.0.115.92")
        assertEquals(401, own.response.status)
        assertEquals(401, other.response.status)
    }

    @Test
    fun `I1 - 100KB of dotted letters is a 400 or INVALID on all four public paths, never a 500`() {
        val huge = "a" + ".a".repeat(50_000)
        check("\"$huge\"", "203.0.113.40").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.reason").value("INVALID")).andExpect(jsonPath("$.value.code").value("Size"))
            .andExpect(jsonPath("$.value.normalized").value(org.hamcrest.Matchers.hasLength(org.hamcrest.Matchers.lessThanOrEqualTo(30))))
        signUp(unique(), "\"$huge\"").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("loginId")).andExpect(jsonPath("$.errors[0].code").value("Size"))
        val first = signUp(unique(), "\"${id()}\"").andExpect(status().isAccepted)
        verify(signUpId(first), lastCode(), "\"$huge\"").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("loginId"))
        val (email, _) = member()
        val token = JsonPath.read<String>(login("""{"email":"$email","password":"tangerine-42-moon"}""", "203.0.113.41").response.contentAsString, "$.value.accessToken")
        mvc.perform(post("/api/v1/account/login-id").header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON).content("""{"loginId":"$huge"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("loginId"))
    }

    @Test
    fun `I1 - a 100KB username in the login field is a 400 from the body limit, never a 500`() {
        val huge = "a" + ".a".repeat(50_000)
        repeat(2) { assertEquals(400, login("""{"username":"$huge","password":"x-password-1234"}""", "203.0.116.${1 + it}").response.status) }
    }

    // ---- L9 — POST /account/login-id/forgot (아이디 찾기)

    private fun forgot(email: String, ip: String, extra: String = ""): ResultActions =
        mvc.perform(post("/api/v1/account/login-id/forgot").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email"$extra}"""))

    @Test
    fun `L9 - public and always 202 ACCEPTED, the same body for a known and an unknown address, and the mail goes to the account address with the id`() {
        val email = unique(); val loginId = id()
        verify(signUpId(signUp(email, "\"$loginId\"")), lastCode()).andExpect(status().isOk)
        mail.sent.clear()
        val known = forgot(email.uppercase(), "203.0.113.31").andExpect(status().isAccepted).andExpect(jsonPath("$.value.status").value("ACCEPTED")).andReturn().response.contentAsString
        val unknown = forgot(unique(), "203.0.113.31").andExpect(status().isAccepted).andReturn().response.contentAsString
        assertEquals(JsonPath.read<Any>(known, "$.value"), JsonPath.read<Any>(unknown, "$.value"), "the same answer (only the trace meta differs)")
        val reminder = mail.of(MailKind.LOGIN_ID_REMINDER).single()
        assertEquals(email, reminder.to)
        assertEquals(loginId, reminder.vars["loginId"])
        assertEquals("https://app.example.com/login", reminder.link)
        assertTrue(mail.of(MailKind.PASSWORD_RESET).isEmpty(), "this is not a password reset")
    }

    @Test
    fun `L9 - the 11th request from one IP is 429 ACCOUNT_RATE_LIMITED with Retry-After - another IP is fine - the password forgot IP bucket is a different one`() {
        val ip = "203.0.113.32"
        repeat(10) { forgot(unique(), ip).andExpect(status().isAccepted) }
        forgot(unique(), ip).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("ACCOUNT.RATE_LIMITED")).andExpect(header().exists("Retry-After"))
        forgot(unique(), "203.0.113.33").andExpect(status().isAccepted)
        mvc.perform(post("/api/v1/account/password/forgot").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"${unique()}"}""")).andExpect(status().isAccepted)
    }

    @Test
    fun `L9 - a malformed address is the usual 400 on email, not a 202`() {
        forgot("not-an-email", "203.0.113.34").andExpect(status().isBadRequest).andExpect(jsonPath("$.errors[0].field").value("email"))
    }

    @Test
    fun `L9 - a signed-in account that already has an id may ask too, and the request body stays out of toString`() {
        val email = unique(); val loginId = id()
        val login = verify(signUpId(signUp(email, "\"$loginId\"")), lastCode()).andExpect(status().isOk).andReturn()
        mail.sent.clear()
        mvc.perform(
            post("/api/v1/account/login-id/forgot").with { it.remoteAddr = "203.0.113.35"; it }.contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + JsonPath.read<String>(login.response.contentAsString, "$.value.accessToken")).content("""{"email":"$email"}"""),
        ).andExpect(status().isAccepted)
        assertEquals(1, mail.of(MailKind.LOGIN_ID_REMINDER).size)
        assertTrue("S3cr3t@example.com" !in LoginIdForgotRequest("S3cr3t@example.com", "S3cr3t-token").toString())
    }
}
