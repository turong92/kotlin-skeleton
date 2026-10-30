package dev.sumin.skeleton.auth.mfa

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.security.AuthPrincipalAuthentication
import dev.sumin.skeleton.auth.security.BreakGlassAuthentication
import dev.sumin.skeleton.auth.security.NonInteractiveAuthentication
import dev.sumin.skeleton.mfatest.FakeSessionInspector
import dev.sumin.skeleton.mfatest.FrozenTime
import dev.sumin.skeleton.mfatest.Mails
import dev.sumin.skeleton.mfatest.MfaTestApplication
import dev.sumin.skeleton.mfatest.MfaTestBeans
import java.time.Duration
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val PASSWORD = "tangerine-42-moon"
private const val ME = "/api/v1/account/me"

/** 「키 요청」 처럼 사람이 아닌 자격 - 2단계 갈래는 api-key 를 모르고 이 표지만 읽는다 */
private class ProgramAuthentication(private val accountId: String) : AbstractAuthenticationToken(emptyList()), NonInteractiveAuthentication {
    init { isAuthenticated = true }
    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = CurrentPrincipal(accountId = accountId)
    override fun getName(): String = accountId
}

/** 필수 문 - `required-for: ALL` 인데 아직 등록 전인 계정은 허용 목록 밖에서 403 `MFA.ENROLLMENT_REQUIRED`, 등록하면 바로 풀린다. 키 요청은 안 걸린다 */
@SpringBootTest(
    classes = [MfaTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-mfa.required-for=ALL",
        "skeleton.auth-mfa.required.extra-exclude-paths=GET /api/v1/account/identities",
        "skeleton.crypto.primary-key-id=local",
        "skeleton.crypto.keys.local=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@AutoConfigureMockMvc
@Import(MfaTestBeans::class)
class MfaRequiredGateWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var time: FrozenTime
    @Autowired lateinit var inspector: FakeSessionInspector

    private class User(val id: String, val bearer: String)

    private var ip = 0

    private fun signUp(): User {
        val email = "g${System.nanoTime()}@example.com"
        val signUp = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = "198.51.100.${150 + ip++ % 90}"; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$PASSWORD"}""")).andReturn()
        val done = mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"${JsonPath.read<String>(signUp.response.contentAsString, "$.value.signUpId")}","code":"${mails.lastCode()}"}""")).andReturn().response.contentAsString
        return User(JsonPath.read(done, "$.value.principal.accountId"), "Bearer " + JsonPath.read<String>(done, "$.value.accessToken"))
    }

    private fun patchMe(user: User): ResultActions = mvc.perform(patch(ME).header("Authorization", user.bearer).contentType(MediaType.APPLICATION_JSON).content("{}"))

    private fun mfa(user: User, path: String, body: String): ResultActions =
        mvc.perform(post("/api/v1/auth/mfa$path").header("Authorization", user.bearer).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun humanAt(user: User, sessionId: String) = authentication(AuthPrincipalAuthentication(CurrentPrincipal(accountId = user.id, sessionId = sessionId)))

    @Test
    fun `a required account that has not enrolled is blocked outside the allow list, and enrolling opens the door at once`() {
        val user = signUp()

        patchMe(user).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("MFA.ENROLLMENT_REQUIRED"))
        mvc.perform(get(ME).header("Authorization", user.bearer)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/auth/mfa").header("Authorization", user.bearer)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/account/identities").header("Authorization", user.bearer)).andExpect(status().isOk)   // extra-exclude-paths
        mvc.perform(patch(ME).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized)   // 익명은 보안 체인의 몫

        val started = mfa(user, "/totp/enrollment", """{"currentPassword":"$PASSWORD"}""").andExpect(status().isOk).andReturn().response.contentAsString
        val secret = Base32.decode(JsonPath.read<String>(started, "$.value.secret"))
        patchMe(user).andExpect(status().isForbidden)   // 시작만으로는 풀리지 않는다
        mfa(user, "/totp/enrollment/confirm", """{"code":"${Totp.code(secret, Totp.stepOf(time.now()) - 1)}"}""").andExpect(status().isOk)

        patchMe(user).andExpect(status().isOk)   // 30초 캐시를 기다리지 않는다
    }

    @Test
    fun `a session opened within 15 minutes enrols without a fresh proof, an older one still needs the proof`() {
        val user = signUp()

        inspector.openedAt["s-old"] = time.now().minus(Duration.ofMinutes(16))
        mvc.perform(post("/api/v1/auth/mfa/totp/enrollment").with(humanAt(user, "s-old")).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))

        inspector.openedAt["s-new"] = time.now().minus(Duration.ofMinutes(14))
        mvc.perform(post("/api/v1/auth/mfa/totp/enrollment").with(humanAt(user, "s-new")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk)
    }

    @Test
    fun `an api key style request is not stopped by the enrolment door but the same account as a person is`() {
        val user = signUp()

        patchMe(user).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("MFA.ENROLLMENT_REQUIRED"))
        val viaKey = mvc.perform(patch(ME).with(authentication(ProgramAuthentication(user.id))).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response.contentAsString
        kotlin.test.assertFalse("MFA.ENROLLMENT_REQUIRED" in viaKey, viaKey)
    }

    @Test
    fun `a break-glass request passes the door - it is the emergency way in - while the same account's ordinary login is blocked`() {
        val user = signUp()

        patchMe(user).andExpect(status().isForbidden)
        val viaBreakGlass = mvc.perform(patch(ME).with(authentication(BreakGlassAuthentication(CurrentPrincipal(accountId = user.id)))).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().response.contentAsString
        kotlin.test.assertFalse("MFA.ENROLLMENT_REQUIRED" in viaBreakGlass, viaBreakGlass)
    }
}
