package dev.sumin.skeleton.auth.mfa

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.mfatest.Events
import dev.sumin.skeleton.mfatest.FakeSessions
import dev.sumin.skeleton.mfatest.FrozenTime
import dev.sumin.skeleton.mfatest.Mails
import dev.sumin.skeleton.mfatest.MfaTestApplication
import dev.sumin.skeleton.mfatest.MfaTestBeans
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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

private val ips = AtomicInteger()
private const val PASSWORD = "tangerine-42-moon"
private const val BASE = "/api/v1/auth/mfa"

/** 진짜 account · auth 위에서 - 등록(다시 인증) · 확인 · 로그인 2단계 · 한도 · 재사용 · 복구 코드 · 끄기 · 재발급 · 재등록 · 탈퇴 · 비밀 원문이 어디에도 없음 */
@SpringBootTest(
    classes = [MfaTestApplication::class],
    properties = [
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-mfa.totp.issuer=Notes",
        "skeleton.crypto.primary-key-id=local",
        "skeleton.crypto.keys.local=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@AutoConfigureMockMvc
@Import(MfaTestBeans::class)
@ExtendWith(OutputCaptureExtension::class)
class MfaWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mails: Mails
    @Autowired lateinit var events: Events
    @Autowired lateinit var store: FakeMfaStore
    @Autowired lateinit var sessions: FakeSessions
    @Autowired lateinit var time: FrozenTime
    @Autowired lateinit var erasures: List<AccountErasureListener>

    private class User(val id: String, val email: String, val bearer: String)
    private class Enabled(val user: User, val secret: ByteArray, val base32: String, val recoveryCodes: List<String>)

    private fun signUp(): User {
        val email = "m${System.nanoTime()}@example.com"
        val ip = "198.51.100.${100 + ips.getAndIncrement() % 100}"
        val sign = mvc.perform(post("/api/v1/account/sign-up").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$PASSWORD"}""")).andExpect(status().isAccepted).andReturn()
        val signUpId = JsonPath.read<String>(sign.response.contentAsString, "$.value.signUpId")
        val done = mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("""{"signUpId":"$signUpId","code":"${mails.lastCode()}"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        return User(JsonPath.read(done, "$.value.principal.accountId"), email, "Bearer " + JsonPath.read<String>(done, "$.value.accessToken"))
    }

    private fun post(user: User?, path: String, body: String): ResultActions =
        mvc.perform(post("$BASE$path").contentType(MediaType.APPLICATION_JSON).content(body).also { b -> user?.let { b.header("Authorization", it.bearer) } })

    private fun proof(password: String = PASSWORD) = """"currentPassword":"$password""""

    private fun startEnrollment(user: User): Pair<ByteArray, String> {
        val body = post(user, "/totp/enrollment", "{${proof()}}").andExpect(status().isOk).andReturn().response.contentAsString
        val base32 = JsonPath.read<String>(body, "$.value.secret")
        return Base32.decode(base32) to base32
    }

    private fun codeAt(secret: ByteArray, delta: Long = 0) = Totp.code(secret, Totp.stepOf(time.now()) + delta)

    /** 6자리인데 지금 창(±1)의 어느 코드도 아닌 값 */
    private fun wrongCode(secret: ByteArray): String =
        (0..9).map { "%06d".format(it * 111_111 + 1) }.first { Totp.match(secret, it, time.now()) == null }

    /** 켠다 - 확인 코드는 한 걸음 전 것(s-1)이라 로그인에는 s, s+1 이 남는다 */
    private fun enable(): Enabled {
        val user = signUp()
        val (secret, base32) = startEnrollment(user)
        val body = post(user, "/totp/enrollment/confirm", """{"code":"${codeAt(secret, -1)}"}""").andExpect(status().isOk).andReturn().response.contentAsString
        return Enabled(user, secret, base32, JsonPath.read(body, "$.value.recoveryCodes"))
    }

    private fun login(user: User): ResultActions =
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"email":"${user.email}","password":"$PASSWORD"}"""))

    private fun mfaToken(user: User): String =
        JsonPath.read(login(user).andExpect(status().isForbidden).andReturn().response.contentAsString, "$.data.mfaToken")

    private fun verify(token: String, factor: String): ResultActions = post(null, "/verify", """{"mfaToken":"$token"${if (factor.isEmpty()) "" else ",$factor"}}""")

    private fun codeFactor(code: String) = """"code":"$code""""

    private fun stateOf(user: User): String = mvc.perform(get(BASE).header("Authorization", user.bearer)).andExpect(status().isOk).andReturn().response.contentAsString

    // ---------------------------------------------------------------- 등록

    @Test
    fun `enrolling needs a fresh proof and returns the secret, the otpauth uri and a QR path once with no-store`() {
        val user = signUp()

        post(user, "/totp/enrollment", "{${proof("wrong-password-1")}}").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        post(user, "/totp/enrollment", "{}").andExpect(status().isBadRequest)
        post(null, "/totp/enrollment", "{${proof()}}").andExpect(status().isUnauthorized)
        assertNull(store.find(user.id), "nothing was started without the proof")

        val ok = post(user, "/totp/enrollment", "{${proof()}}").andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
        val secret = JsonPath.read<String>(ok, "$.value.secret")
        assertTrue(Regex("^[A-Z2-7]{32}$").matches(secret), secret)
        val uri = JsonPath.read<String>(ok, "$.value.otpauthUri")
        assertTrue(uri.startsWith("otpauth://totp/Notes:"), uri)
        assertTrue("secret=$secret" in uri && "issuer=Notes" in uri && "algorithm=SHA1" in uri && "digits=6" in uri && "period=30" in uri, uri)
        assertTrue(user.email.replace("@", "%40") in uri, "the account name is the email: $uri")
        assertTrue(JsonPath.read<String>(ok, "$.value.qr.path").startsWith("M"))
        assertTrue(JsonPath.read<Int>(ok, "$.value.qr.size") > 20)
        assertEquals(time.now().plus(Duration.ofMinutes(10)).toString(), JsonPath.read<String>(ok, "$.value.expiresAt"))

        val record = store.find(user.id)!!
        assertNull(record.secretEnc, "still off until the first code confirms it")
        assertTrue(record.pendingSecretEnc!!.startsWith("aes-gcm:v1:") && secret !in record.pendingSecretEnc!!, "the pending secret is a TextEncryptor envelope")
        assertFalse(stateOf(user).contains("\"enabled\":true"))
    }

    @Test
    fun `the first code turns it on - ten recovery codes once, other sessions closed, event and notice`() {
        val user = signUp()
        val (secret, base32) = startEnrollment(user)

        post(user, "/totp/enrollment/confirm", """{"code":"${wrongCode(secret)}"}""").andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MFA.CODE_INVALID")).andExpect(jsonPath("$.data.attemptsLeft").isNumber)
        assertNull(store.find(user.id)!!.secretEnc)

        val confirmed = post(user, "/totp/enrollment/confirm", """{"code":"${codeAt(secret, -1)}"}""").andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
        val codes = JsonPath.read<List<String>>(confirmed, "$.value.recoveryCodes")
        assertEquals(10, codes.size)
        codes.forEach { assertTrue(Regex("^([A-Z2-7]{4}-){3}[A-Z2-7]{4}$").matches(it), it) }

        val record = store.find(user.id)!!
        assertTrue(record.secretEnc!!.startsWith("aes-gcm:v1:") && base32 !in record.secretEnc!!, "secret_enc is a TextEncryptor envelope, never the plain secret")
        assertNull(record.pendingSecretEnc)
        assertNotNull(record.enabledAt)
        assertEquals(Totp.stepOf(time.now()) - 1, record.lastUsedStep, "the code that confirmed cannot be used again")
        assertEquals(10, store.recoveryCodesRemaining(user.id))

        val state = stateOf(user)
        assertEquals(true, JsonPath.read<Boolean>(state, "$.value.enabled"))
        assertEquals(10, JsonPath.read<Int>(state, "$.value.recoveryCodesRemaining"))
        assertEquals(false, JsonPath.read<Boolean>(state, "$.value.required"))

        assertTrue(sessions.revoked.any { it.first == user.id }, "the other sessions of this account were closed")
        assertEquals("enrollment", events.of(AccountEventType.MFA_ENABLED, user.id).single().detail["via"])
        assertEquals(1, mails.notices(user.email, "enabled").size)
        post(user, "/totp/enrollment/confirm", """{"code":"${codeAt(secret)}"}""").andExpect(status().isGone).andExpect(jsonPath("$.code").value("MFA.ENROLLMENT_EXPIRED"))
    }

    @Test
    fun `an enrollment that waits past its ten minutes cannot be confirmed`() {
        val user = signUp()
        val (secret, _) = startEnrollment(user)
        time.advance(Duration.ofMinutes(11))

        post(user, "/totp/enrollment/confirm", """{"code":"${codeAt(secret)}"}""").andExpect(status().isGone).andExpect(jsonPath("$.code").value("MFA.ENROLLMENT_EXPIRED"))
        assertNull(store.find(user.id)!!.secretEnc)
    }

    // ---------------------------------------------------------------- 로그인 2단계

    @Test
    fun `an account without it logs in as before, and with it the password alone only gets a challenge`() {
        val plain = signUp()
        login(plain).andExpect(status().isOk).andExpect(jsonPath("$.value.accessToken").isString)

        val on = enable()
        val challenge = login(on.user).andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("AUTH.MFA_REQUIRED")).andReturn().response.contentAsString
        assertEquals(listOf("totp", "recovery_code"), JsonPath.read<List<String>>(challenge, "$.data.methods"))
        assertEquals(time.now().plus(Duration.ofMinutes(5)).toString(), JsonPath.read<String>(challenge, "$.data.expiresAt"))
        assertFalse("accessToken" in challenge)
    }

    @Test
    fun `a right code finishes the login with the same response, once, and the tokens work`() {
        val on = enable()
        val token = mfaToken(on.user)

        verify(token, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MFA.CODE_INVALID")).andExpect(jsonPath("$.data.attemptsLeft").value(4))
        val ok = verify(token, codeFactor(codeAt(on.secret))).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(on.user.id, JsonPath.read<String>(ok, "$.value.principal.accountId"))
        mvc.perform(get("/api/v1/account/me").header("Authorization", "Bearer " + JsonPath.read<String>(ok, "$.value.accessToken"))).andExpect(status().isOk)

        assertEquals("totp", events.of(AccountEventType.MFA_VERIFIED, on.user.id).single().detail["method"])
        assertEquals(1, events.of(AccountEventType.MFA_FAILED, on.user.id).size)
        verify(token, codeFactor(codeAt(on.secret, 1))).andExpect(status().isGone).andExpect(jsonPath("$.code").value("MFA.CHALLENGE_EXPIRED"))
    }

    @Test
    fun `the same code does not pass twice - the second login in the same step is wrong`() {
        val on = enable()
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret))).andExpect(status().isOk)

        verify(mfaToken(on.user), codeFactor(codeAt(on.secret))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MFA.CODE_INVALID"))
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret, -1))).andExpect(status().isBadRequest)   // 더 옛 걸음도 안 받는다
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret, 1))).andExpect(status().isOk)            // 새 걸음은 받는다
    }

    @Test
    fun `one challenge allows five wrong tries and then it is gone even for the right code`() {
        val on = enable()
        val token = mfaToken(on.user)

        (4 downTo 0).forEach { left ->
            verify(token, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest).andExpect(jsonPath("$.data.attemptsLeft").value(left))
        }
        verify(token, codeFactor(codeAt(on.secret))).andExpect(status().isGone).andExpect(jsonPath("$.code").value("MFA.CHALLENGE_EXPIRED"))
        verify("x".repeat(43), codeFactor(codeAt(on.secret))).andExpect(status().isGone)
    }

    @Test
    fun `a challenge that waits more than five minutes is gone`() {
        val on = enable()
        val token = mfaToken(on.user)
        time.advance(Duration.ofMinutes(6))

        verify(token, codeFactor(codeAt(on.secret))).andExpect(status().isGone).andExpect(jsonPath("$.code").value("MFA.CHALLENGE_EXPIRED"))
    }

    @Test
    fun `ten wrong codes in a window block the account with 429, one mail, and not even the right code passes`() {
        val on = enable()
        repeat(2) {
            val token = mfaToken(on.user)
            repeat(5) { verify(token, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest) }
        }
        assertEquals(emptyList(), mails.notices(on.user.email, "throttled"))

        val blocked = mfaToken(on.user)
        verify(blocked, codeFactor(wrongCode(on.secret))).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("MFA.TOO_MANY_ATTEMPTS")).andExpect(header().exists("Retry-After"))
        verify(blocked, codeFactor(codeAt(on.secret))).andExpect(status().isTooManyRequests)
        verify(blocked, codeFactor(wrongCode(on.secret))).andExpect(status().isTooManyRequests)

        assertEquals(1, mails.notices(on.user.email, "throttled").size, "one mail per window")
        assertEquals(1, events.of(AccountEventType.MFA_THROTTLED, on.user.id).size)
        time.advance(Duration.ofMinutes(16))
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret))).andExpect(status().isOk)   // 창이 지나면 다시 된다
    }

    @Test
    fun `right codes are not counted against the limit`() {
        val on = enable()
        val first = mfaToken(on.user)
        repeat(5) { verify(first, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest) }
        val second = mfaToken(on.user)
        repeat(4) { verify(second, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest) }   // 9번 틀림
        verify(second, codeFactor(codeAt(on.secret))).andExpect(status().isOk)
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret, 1))).andExpect(status().isOk)               // 맞은 시도가 셌다면 여기서 이미 11번째

        val third = mfaToken(on.user)
        verify(third, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest)                   // 10번째
        verify(third, codeFactor(wrongCode(on.secret))).andExpect(status().isTooManyRequests)              // 11번째
    }

    @Test
    fun `a recovery code logs in once, is counted, and typing it loosely finds the same code`() {
        val on = enable()
        val code = on.recoveryCodes[0]

        verify(mfaToken(on.user), """"recoveryCode":"$code"""").andExpect(status().isOk)
        assertEquals(9, JsonPath.read<Int>(stateOf(on.user), "$.value.recoveryCodesRemaining"))
        assertEquals("9", events.of(AccountEventType.MFA_RECOVERY_CODE_USED, on.user.id).single().detail["remaining"])
        assertEquals("recovery_code", events.of(AccountEventType.MFA_VERIFIED, on.user.id).single().detail["method"])
        assertEquals(1, mails.notices(on.user.email, "recovery_code_used").size)

        verify(mfaToken(on.user), """"recoveryCode":"$code"""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MFA.CODE_INVALID"))
        verify(mfaToken(on.user), """"recoveryCode":"${on.recoveryCodes[1].replace("-", "").lowercase()}"""").andExpect(status().isOk)
    }

    @Test
    fun `a request must name exactly one factor`() {
        val on = enable()
        val token = mfaToken(on.user)

        verify(token, "").andExpect(status().isBadRequest)
        verify(token, """"code":"${codeAt(on.secret)}","recoveryCode":"${on.recoveryCodes[0]}"""").andExpect(status().isBadRequest)
        verify(token, """"code":"12ab56"""").andExpect(status().isBadRequest)
        verify(token, codeFactor(codeAt(on.secret))).andExpect(status().isOk)   // 거절된 요청은 시도를 쓰지 않았다
    }

    // ---------------------------------------------------------------- 끄기 · 재발급 · 재등록

    @Test
    fun `turning it off needs the proof and a code`() {
        val on = enable()
        val body = { password: String, factor: String -> """{${proof(password)},$factor}""" }

        post(on.user, "/disable", "{${proof()}}").andExpect(status().isBadRequest)
        post(on.user, "/disable", body("wrong-password-1", codeFactor(codeAt(on.secret)))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ACCOUNT.CURRENT_PASSWORD_INVALID"))
        post(on.user, "/disable", body(PASSWORD, codeFactor(wrongCode(on.secret)))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("MFA.CODE_INVALID"))
        post(null, "/disable", body(PASSWORD, codeFactor(codeAt(on.secret)))).andExpect(status().isUnauthorized)
        assertTrue(store.find(on.user.id)!!.enabled)

        post(on.user, "/disable", body(PASSWORD, codeFactor(codeAt(on.secret)))).andExpect(status().isNoContent)
        assertNull(store.find(on.user.id))
        assertEquals(0, store.recoveryCodesRemaining(on.user.id))
        assertEquals(1, events.of(AccountEventType.MFA_DISABLED, on.user.id).size)
        assertEquals(1, mails.notices(on.user.email, "disabled").size)
        login(on.user).andExpect(status().isOk)
        post(on.user, "/disable", body(PASSWORD, codeFactor("123456"))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("MFA.NOT_ENABLED"))
    }

    @Test
    fun `a recovery code can turn it off as well`() {
        val on = enable()
        post(on.user, "/disable", """{${proof()},"recoveryCode":"${on.recoveryCodes[3]}"}""").andExpect(status().isNoContent)
        assertNull(store.find(on.user.id))
    }

    @Test
    fun `new recovery codes need the proof, replace the old ones and are shown once`() {
        val on = enable()

        post(on.user, "/recovery-codes", "{${proof("wrong-password-1")}}").andExpect(status().isBadRequest)
        post(null, "/recovery-codes", "{${proof()}}").andExpect(status().isUnauthorized)
        val fresh = JsonPath.read<List<String>>(post(on.user, "/recovery-codes", "{${proof()}}").andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString, "$.value.recoveryCodes")

        assertEquals(10, fresh.size)
        assertTrue(fresh.intersect(on.recoveryCodes.toSet()).isEmpty())
        assertEquals(1, events.of(AccountEventType.MFA_RECOVERY_CODES_REGENERATED, on.user.id).size)
        verify(mfaToken(on.user), """"recoveryCode":"${on.recoveryCodes[0]}"""").andExpect(status().isBadRequest)
        verify(mfaToken(on.user), """"recoveryCode":"${fresh[0]}"""").andExpect(status().isOk)
        post(signUp(), "/recovery-codes", "{${proof()}}").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("MFA.NOT_ENABLED"))
    }

    @Test
    fun `enrolling again keeps the old secret alive until the new one is confirmed, and keeps the recovery codes`() {
        val on = enable()
        val (newSecret, _) = startEnrollment(on.user)

        verify(mfaToken(on.user), codeFactor(codeAt(on.secret))).andExpect(status().isOk)   // 아직 옛 비밀
        post(on.user, "/totp/enrollment/confirm", """{"code":"${wrongCode(newSecret)}"}""").andExpect(status().isBadRequest)
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret, 1))).andExpect(status().isOk)

        post(on.user, "/totp/enrollment/confirm", """{"code":"${codeAt(newSecret)}"}""").andExpect(status().isOk).andExpect(jsonPath("$.value.recoveryCodes").doesNotExist())
        assertEquals(10, store.recoveryCodesRemaining(on.user.id), "the existing recovery codes stay")
        assertEquals("re_enrollment", events.of(AccountEventType.MFA_ENABLED, on.user.id).last().detail["via"])
        assertNull(store.find(on.user.id)!!.pendingSecretEnc)

        time.advance(Duration.ofSeconds(60))
        verify(mfaToken(on.user), codeFactor(codeAt(on.secret))).andExpect(status().isBadRequest)   // 옛 비밀은 죽었다
        verify(mfaToken(on.user), codeFactor(codeAt(newSecret))).andExpect(status().isOk)
    }

    // ---------------------------------------------------------------- 탈퇴 · 원문

    @Test
    fun `erasing the account removes the secret and the recovery codes`() {
        val on = enable()
        val listener = erasures.single { it.name == "auth-mfa" }

        listener.erase(ErasureRequest(on.user.id, "tombstone"))
        listener.erase(ErasureRequest(on.user.id, "tombstone"))

        assertNull(store.find(on.user.id))
        assertEquals(0, store.recoveryCodesRemaining(on.user.id))
    }

    @Test
    fun `no secret, recovery code, code or mfa token is in the store, the logs or the events in the clear`(log: CapturedOutput) {
        val on = enable()
        val token = mfaToken(on.user)
        val code = codeAt(on.secret)
        verify(token, codeFactor(wrongCode(on.secret))).andExpect(status().isBadRequest)
        verify(token, codeFactor(code)).andExpect(status().isOk)
        verify(mfaToken(on.user), """"recoveryCode":"${on.recoveryCodes[0]}"""").andExpect(status().isOk)

        val seen = events.all.joinToString("\n") { "$it" } + "\n" + log.all + "\n" + store.dump() + "\n" + store.find(on.user.id)
        (listOf(on.base32, token, code) + on.recoveryCodes + on.recoveryCodes.map { it.replace("-", "") }).forEach {
            assertFalse(it in seen, "'$it' must not appear in the store, the logs or the events")
        }
        assertTrue(store.dump().contains(RecoveryCodes.hash(on.recoveryCodes[0])), "recovery codes are kept as SHA-256")
    }
}
