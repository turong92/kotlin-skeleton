package dev.sumin.skeleton.app.sample.mfa

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.auth.mfa.Base32
import dev.sumin.skeleton.auth.mfa.Totp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

/**
 * 견본 앱의 2단계 인증(스펙 §6 ① ~ ④ 의 백엔드 쪽) - 사용자가 켠다 → 로그인은 403 → 코드로 들어감 · 복구 코드로 들어감(남은 9) · 같은 코드 다시 → 거절 ·
 * 운영자는 등록 전까지 다른 API 가 403(방금 로그인한 세션은 다시 인증 없이 등록) → 등록하면 열림 → 운영자가 사용자의 2단계를 해제 → 사용자는 코드 없이 로그인.
 * 시험 설정은 `required-for: NONE` 이라 여기서만 견본의 선택(OPERATORS)으로 켠다. 진짜 PostgreSQL · 보안 체인 · Flyway.
 */
@TestPropertySource(properties = ["skeleton.auth-mfa.required-for=OPERATORS", "skeleton.auth-social.providers.fakeidp.enabled=true"])
@Import(MfaJourneyIntegrationTest.Beans::class)
class MfaJourneyIntegrationTest : CheerIntegrationBase() {
    @TestConfiguration(proxyBeanMethods = false)
    class Beans {
        val sent = CopyOnWriteArrayList<AccountMail>()
        @Bean fun recordingMailer(): AccountMailer = AccountMailer { sent += it }
        @Bean fun directTasks(): AccountTaskRunner = AccountTaskRunner.DIRECT
        @Bean fun fakeOAuthProvider(): OAuthProvider = object : OAuthProvider {
            override val providerId = "fakeidp"
            override fun fetchProfile(authorizationCode: String, redirectUri: String?) =
                OAuthUserProfile(providerId, "sub-$authorizationCode", "$authorizationCode@example.com", "$authorizationCode@example.com", "Social", emailVerified = true)
        }
    }

    @Autowired lateinit var beans: Beans
    @Autowired lateinit var core: AccountCore

    @AfterEach
    fun wipeMfa() {
        listOf("mfa_recovery_codes", "mfa_totp").forEach { jdbc.sql("delete from $it").update() }
    }

    private class Enabled(val secret: ByteArray, val recoveryCodes: List<String>)

    private fun mfa(path: String, token: String?, body: String) = post("/api/v1/auth/mfa$path", token, body)

    private fun codeAt(secret: ByteArray, delta: Long) = Totp.code(secret, Totp.stepOf(Instant.now()) + delta)

    /** 켠다 - 확인에는 지금 걸음의 코드를 쓰므로 로그인에는 다음 걸음(+1, 허용 범위 안)의 코드를 쓴다 */
    private fun enable(token: String, proof: String = """"currentPassword":"password""""): Enabled {
        val started = mfa("/totp/enrollment", token, "{$proof}").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val secret = Base32.decode(JsonPath.read<String>(started, "$.value.secret"))
        val confirmed = mfa("/totp/enrollment/confirm", token, """{"code":"${codeAt(secret, 0)}"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        return Enabled(secret, JsonPath.read(confirmed, "$.value.recoveryCodes"))
    }

    private fun loginFor(email: String): ResultActionsDsl =
        mvc.post("/api/v1/auth/login") { contentType = MediaType.APPLICATION_JSON; content = """{"email":"$email","password":"password"}""" }

    private fun mfaToken(email: String): String =
        JsonPath.read(loginFor(email).andExpect { status { isForbidden() }; jsonPath("$.code") { value("AUTH.MFA_REQUIRED") } }.andReturn().response.contentAsString, "$.data.mfaToken")

    private fun verify(token: String, factor: String) = post("/api/v1/auth/mfa/verify", null, """{"mfaToken":"$token",$factor}""")

    @Test
    fun `a user turns it on, signs in with a code, with a recovery code, and a used code is refused`() {
        val on = enable(user)

        val first = verify(mfaToken("user@example.com"), """"code":"${codeAt(on.secret, 1)}"""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertTrue(JsonPath.read<String>(first, "$.value.accessToken").isNotBlank())

        verify(mfaToken("user@example.com"), """"code":"${codeAt(on.secret, 1)}"""").andExpect { status { isBadRequest() }; jsonPath("$.code") { value("MFA.CODE_INVALID") } }   // 같은 걸음의 코드는 두 번 못 쓴다

        val recovered = verify(mfaToken("user@example.com"), """"recoveryCode":"${on.recoveryCodes.first()}"""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val token = JsonPath.read<String>(recovered, "$.value.accessToken")
        get("/api/v1/auth/mfa", token).andExpect { status { isOk() }; jsonPath("$.value.recoveryCodesRemaining") { value(9) } }
    }

    @Test
    fun `an operator is stopped until enrolled, a fresh login enrols without a proof, and then the door is open`() {
        get("/api/v1/notes", operator).andExpect { status { isForbidden() }; jsonPath("$.code") { value("MFA.ENROLLMENT_REQUIRED") } }
        get("/api/v1/account/me", operator).andExpect { status { isOk() } }
        get("/api/v1/notes", user).andExpect { status { isOk() } }   // 운영 권한이 없는 사용자에게는 필수가 아니다

        enable(operator, proof = "")   // 방금 로그인한 세션 - 다시 인증 증거 없이

        get("/api/v1/notes", operator).andExpect { status { isOk() } }
    }

    @Test
    fun `an operator resets a user's second factor and the user signs in without a code`() {
        enable(user)
        enable(operator, proof = "")
        val userId = accountOf("user@example.com")

        post("/api/v1/admin/mfa/accounts/$userId/reset", user, """{"reason":"verified by video call"}""").andExpect { status { isForbidden() } }
        post("/api/v1/admin/mfa/accounts/$userId/reset", operator, """{"reason":"verified by video call"}""").andExpect { status { isNoContent() } }

        loginFor("user@example.com").andExpect { status { isOk() } }
        assertEquals(0, jdbc.sql("select count(*) from mfa_totp where account_id = :a").param("a", userId).query(Int::class.java).single())
    }

    // ------------------------------------------------ 다른 로그인 길도 두 번째 단계를 지나 토큰을 받는다 (소셜 · 매직 링크 · 탈퇴 취소)

    private fun challengeThenVerify(accountId: String, secret: ByteArray, second: () -> ResultActionsDsl) {
        val body = second().andExpect { status { isForbidden() }; jsonPath("$.code") { value("AUTH.MFA_REQUIRED") } }.andReturn().response.contentAsString
        assertTrue("accessToken" !in body, "no token on the first step")
        val verified = verify(JsonPath.read(body, "$.data.mfaToken"), """"code":"${codeAt(secret, 1)}"""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(accountId, JsonPath.read<String>(verified, "$.value.principal.accountId"))
        assertTrue(JsonPath.read<String>(verified, "$.value.accessToken").isNotBlank())
    }

    @Test
    fun `social login meets the challenge and the code finishes the sign-in`() {
        val code = "c${System.nanoTime()}"
        fun social() = post("/api/v1/auth/social/fakeidp/login", null, """{"authorizationCode":"$code"}""")
        val accountId = JsonPath.read<String>(social().andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.principal.accountId")
        val token = JsonPath.read<String>(social().andReturn().response.contentAsString, "$.value.accessToken")
        post("/api/v1/account/login-id", token, """{"loginId":"soc.${code.takeLast(9)}"}""").andExpect { status { isOk() } }   // 이 앱은 아이디를 먼저 정하게 한다
        post("/api/v1/account/reauth/confirmation", token).andExpect { status { isAccepted() } }   // 비밀번호가 없는 계정의 다시 인증 - 메일 6자리
        val on = enable(token, proof = """"confirmationCode":"${beans.sent.last { it.kind == MailKind.REAUTH_CODE }.vars.getValue("code")}"""")

        challengeThenVerify(accountId, on.secret) { social() }
    }

    @Test
    fun `magic link meets the challenge and the code finishes the sign-in`() {
        val on = enable(user)
        fun link(): String { post("/api/v1/auth/magic-link/request", null, """{"email":"user@example.com"}""").andExpect { status { isAccepted() } }; return beans.sent.last { it.kind == MailKind.MAGIC_LINK }.link!!.substringAfter("token=") }

        challengeThenVerify(accountOf("user@example.com"), on.secret) { post("/api/v1/auth/magic-link/redeem", null, """{"token":"${link()}"}""") }
    }

    @Test
    fun `a pending deletion is cancelled only after the second step - login asks for the code, verify hands out the restore token, the cancel does not ask again`() {
        val on = enable(user)
        post("/api/v1/account/delete", user, """{"currentPassword":"password"}""", UUID.randomUUID().toString()).andExpect { status { isAccepted() } }

        val mfaToken = mfaToken("user@example.com")   // 비밀번호만으로는 취소 토큰도 못 받는다
        val restoreToken = JsonPath.read<String>(
            verify(mfaToken, """"code":"${codeAt(on.secret, 1)}"""").andExpect { status { isForbidden() }; jsonPath("$.code") { value("AUTH.ACCOUNT_DELETION_PENDING") } }.andReturn().response.contentAsString,
            "$.data.restoreToken",
        )

        val cancelled = post("/api/v1/account/delete/cancel", null, """{"restoreToken":"$restoreToken"}""").andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertEquals(accountOf("user@example.com"), JsonPath.read<String>(cancelled, "$.value.principal.accountId"))
        loginFor("user@example.com").andExpect { status { isForbidden() }; jsonPath("$.code") { value("AUTH.MFA_REQUIRED") } }   // 계정은 돌아왔다 - 이제 2단계를 묻는다
    }

    @Test
    fun `a restore token without the second-factor mark cannot cancel a deletion of an account that has it on`() {
        enable(user)
        post("/api/v1/account/delete", user, """{"currentPassword":"password"}""", UUID.randomUUID().toString()).andExpect { status { isAccepted() } }
        val id = accountOf("user@example.com")
        val unmarked = core.tokens.issue(TokenPurposes.DELETION_RESTORE, id, id, java.time.Duration.ofMinutes(10), payload = "password")

        post("/api/v1/account/delete/cancel", null, """{"restoreToken":"$unmarked"}""").andExpect { status { isGone() } }

        assertEquals("DELETED", jdbc.sql("select status from accounts where id = :i").param("i", id).query(String::class.java).single())
        // 시험이 계정을 되돌려 놓는다 - 다음 시험의 로그인이 막히지 않게
        jdbc.sql("update accounts set status = 'ACTIVE' where id = :i").param("i", id).update()
    }
}
