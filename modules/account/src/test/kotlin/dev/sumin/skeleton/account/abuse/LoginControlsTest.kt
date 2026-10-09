package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.api.AuthErrorCode
import dev.sumin.skeleton.auth.login.LoginAttempt
import dev.sumin.skeleton.auth.login.LoginFailure
import dev.sumin.skeleton.common.ApplicationException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginControlsTest {
    private val h = AccountHarness()
    private val throttle = LoginThrottle(h.core)
    private val recorder = LoginRecorder(h.core)
    private fun attempt(id: String = "email:ann@example.com", ip: String? = "203.0.113.1") = LoginAttempt(id, ip)

    @Test
    fun `the eleventh attempt on one identifier in the window is refused, whether or not the account exists`() {
        repeat(10) { throttle.beforeAttempt(attempt("email:nobody@example.com", "198.51.100.$it")) }
        val ex = assertFailsWith<ApplicationException> { throttle.beforeAttempt(attempt("email:nobody@example.com", "198.51.100.99")) }
        assertEquals(AuthErrorCode.TOO_MANY_ATTEMPTS, ex.errorCode)
        assertNotNull((ex.data as Map<*, *>)["retryAfterSeconds"])
    }

    @Test
    fun `one client IP is capped across identifiers`() {
        repeat(30) { throttle.beforeAttempt(attempt("email:u$it@example.com", "203.0.113.50")) }
        assertFailsWith<ApplicationException> { throttle.beforeAttempt(attempt("email:another@example.com", "203.0.113.50")) }
        throttle.beforeAttempt(attempt("email:another@example.com", "203.0.113.51"))
    }

    @Test
    fun `the window slides away and the identifier can try again`() {
        repeat(10) { throttle.beforeAttempt(attempt()) }
        assertFailsWith<ApplicationException> { throttle.beforeAttempt(attempt()) }
        h.time.advance(Duration.ofMinutes(11))
        throttle.beforeAttempt(attempt())
    }

    @Test
    fun `identifiers that differ only in case or spaces share one bucket`() {
        repeat(5) { throttle.beforeAttempt(attempt("email:Ann@Example.com")) }
        repeat(5) { throttle.beforeAttempt(attempt("email: ann@example.com ")) }
        assertFailsWith<ApplicationException> { throttle.beforeAttempt(attempt("email:ANN@EXAMPLE.COM")) }
    }

    // ---- B5 (리뷰 I2 로 바뀜): 아이디 로그인은 입력 방식마다 따로 센다 — 아이디로 읽힌 `username` 은 `loginId:<입력>` 버킷, 아이디로 읽히지 않는 `username` 만 이메일 버킷

    private fun idHarness() = AccountHarness(
        AccountProperties(
            mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"), password = AccountProperties.Password(bcryptStrength = 4),
            loginId = AccountProperties.LoginId(enabled = true),
        ),
    )

    private fun AccountHarness.account(id: String, loginId: String, email: String?) {
        val now = time.now()
        repo.insert(Account(id, email, email != null, AccountStatus.ACTIVE, setOf("USER"), null, "ko", "Asia/Seoul", now, now, loginId = loginId), emptyList(), core.loginIdHashOf(loginId))
    }

    @Test
    fun `a login id has its own bucket - the email bucket and the id bucket of one account are separate (I2)`() {
        val h = idHarness(); h.account("acc_1", "ann.kim", "ann@example.com")
        val t = LoginThrottle(h.core)
        repeat(10) { t.beforeAttempt(attempt("username:Ann.Kim", "198.51.100.$it")) }
        val ex = assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("username:ann.kim", "198.51.100.98")) }
        assertEquals(AuthErrorCode.TOO_MANY_ATTEMPTS, ex.errorCode)
        repeat(10) { t.beforeAttempt(attempt("email:ann@example.com", "198.51.100.${20 + it}")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("email:ann@example.com", "198.51.100.97")) }
    }

    @Test
    fun `with the email bucket full a known id and an unknown id are answered the same - nothing says the id belongs to that email (I2)`() {
        val h = idHarness(); h.account("acc_1", "ann.kim", "ann@example.com")
        val t = LoginThrottle(h.core)
        repeat(10) { t.beforeAttempt(attempt("email:ann@example.com", "198.51.100.$it")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("email:ann@example.com", "198.51.100.90")) }
        t.beforeAttempt(attempt("username:ann.kim", "198.51.100.91"))      // the account's own id: not throttled
        t.beforeAttempt(attempt("username:no.such.id", "198.51.100.92"))   // an unknown id: not throttled either
    }

    @Test
    fun `with the id bucket full the account's email is still open, and a login id attempt never reads the repository (I2)`() {
        val h = idHarness(); h.account("acc_1", "ann.kim", "ann@example.com")
        val t = LoginThrottle(h.core)
        val before = h.callLog.calls.size
        repeat(10) { t.beforeAttempt(attempt("username:ann.kim", "198.51.100.$it")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("username:ann.kim", "198.51.100.90")) }
        assertEquals(before, h.callLog.calls.size, "the bucket is the typed id - the same work for an existing and a missing account")
        t.beforeAttempt(attempt("email:ann@example.com", "198.51.100.91"))
    }

    @Test
    fun `an accountId input still spends the account's email bucket, as before`() {
        val h = idHarness(); h.account("acc_1", "ann.kim", "ann@example.com")
        val t = LoginThrottle(h.core)
        repeat(5) { t.beforeAttempt(attempt("accountId:acc_1", "198.51.100.$it")) }
        repeat(5) { t.beforeAttempt(attempt("email:ann@example.com", "198.51.100.${10 + it}")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("accountId:acc_1", "198.51.100.99")) }
    }

    @Test
    fun `a huge login id input is counted by a short key - no 100KB key in the store (I1)`() {
        val h = idHarness()
        val t = LoginThrottle(h.core)
        val huge = "a.".repeat(50_000)
        repeat(10) { t.beforeAttempt(attempt("username:$huge", "198.51.100.$it")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("username:$huge", "198.51.100.99")) }
    }

    @Test
    fun `an unknown login id is counted too, whatever the letter case`() {
        val h = idHarness()
        val t = LoginThrottle(h.core)
        repeat(5) { t.beforeAttempt(attempt("username:Ghost.Id", "198.51.100.$it")) }
        repeat(5) { t.beforeAttempt(attempt("username: ghost.id ", "198.51.100.${10 + it}")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("username:GHOST.ID", "198.51.100.99")) }
        t.beforeAttempt(attempt("username:other.ghost", "198.51.100.99"))
    }

    @Test
    fun `with login ids off a username is still an email bucket`() {
        val t = throttle
        repeat(5) { t.beforeAttempt(attempt("username:ann@example.com", "198.51.100.$it")) }
        repeat(5) { t.beforeAttempt(attempt("email:ANN@example.com", "198.51.100.${10 + it}")) }
        assertFailsWith<ApplicationException> { t.beforeAttempt(attempt("username:ann@example.com", "198.51.100.99")) }
    }

    // ---- M3: 이벤트 id — 아이디로 읽힌 입력은 열쇠 있는 해시, 이메일 · accountId 입력은 그대로

    private fun failureId(h: AccountHarness, identifier: String): String {
        LoginRecorder(h.core).onFailure(attempt(identifier), LoginFailure.UNKNOWN_ACCOUNT, null)
        return h.events.all.last { it.type == AccountEventType.LOGIN_FAILURE }.detail["id"]!!
    }

    @Test
    fun `the event id of a login id input is keyed - a dictionary of ids cannot reproduce it, and letter case does not change it (M3)`() {
        val h = idHarness()
        val a = failureId(h, "username:Ann.Kim"); val b = failureId(h, "username: ann.kim ")
        assertEquals(a, b, "the typed id is normalized first")
        assertEquals(24, a.length)
        listOf("username:Ann.Kim", "username:ann.kim", "ann.kim", "username: ann.kim ").forEach { assertTrue(a != AccountRateLimits.digest(it), "an unkeyed digest of $it matches") }
    }

    @Test
    fun `the throttle event of a login id input carries the keyed id too, and the id of an email or accountId input is the old digest (M3)`() {
        val h = idHarness()
        val t = LoginThrottle(h.core)
        repeat(11) { runCatching { t.beforeAttempt(attempt("username:ann.kim", "198.51.100.$it")) } }
        assertEquals(failureId(h, "username:ann.kim"), h.events.all.single { it.type == AccountEventType.LOGIN_THROTTLED }.detail["id"])
        assertEquals(AccountRateLimits.digest("email:ann@example.com"), failureId(h, "email:ann@example.com"))
        assertEquals(AccountRateLimits.digest("accountId:acc_9"), failureId(h, "accountId:acc_9"))
        assertEquals(AccountRateLimits.digest("username:ann@example.com"), failureId(h, "username:ann@example.com"), "an at sign makes it an email even in the username field")
    }

    @Test
    fun `with login ids off the event id of a username is the old digest (M3)`() {
        assertEquals(AccountRateLimits.digest("username:ann.kim"), failureId(h, "username:ann.kim"))
    }

    @Test
    fun `throttling can be switched off`() {
        val off = AccountHarness(AccountProperties(login = AccountProperties.Login(throttleEnabled = false), mail = AccountProperties.Mail(linkBaseUrl = "https://x")))
        val t = LoginThrottle(off.core)
        repeat(100) { t.beforeAttempt(attempt()) }
    }

    @Test
    fun `the first refusal in a window is reported once as an event, not once per request`() {
        repeat(10) { throttle.beforeAttempt(attempt()) }
        repeat(5) { assertFailsWith<ApplicationException> { throttle.beforeAttempt(attempt()) } }
        assertEquals(1, h.events.all.count { it.type == AccountEventType.LOGIN_THROTTLED })
    }

    @Test
    fun `a failed login is recorded with the account when known and a hash when not - never the email`() {
        val a = h.activeAccount()
        val auth = AuthAccount(a.id, "ann@example.com", "ann@example.com", "", setOf("USER"))
        recorder.onFailure(attempt(), LoginFailure.BAD_PASSWORD, auth)
        recorder.onFailure(attempt("email:ghost@example.com"), LoginFailure.UNKNOWN_ACCOUNT, null)

        val failures = h.events.all.filter { it.type == AccountEventType.LOGIN_FAILURE }
        assertEquals(a.id, failures[0].accountId)
        assertEquals("BAD_PASSWORD", failures[0].detail["reason"])
        assertEquals(null, failures[1].accountId)
        assertEquals("203.0.113.1", failures[1].ip)
        assertTrue(failures.none { e -> e.detail.values.any { "ghost" in it || "@" in it } }, "no email in event details")
        assertTrue(failures[1].detail["id"]!!.length >= 12)
    }

    @Test
    fun `a successful login is recorded and stamps the account`() {
        val a = h.activeAccount()
        h.time.advance(Duration.ofMinutes(5))
        recorder.onSuccess(attempt(), AuthAccount(a.id, "ann@example.com", "ann@example.com", "", setOf("USER")))
        assertEquals(h.time.now(), h.repo.findById(a.id)!!.lastLoginAt)
        assertEquals(h.time.now(), h.repo.findIdentity("password", "ann@example.com")!!.lastUsedAt)
        assertTrue(h.events.all.any { it.type == AccountEventType.LOGIN_SUCCESS && it.accountId == a.id && it.detail["method"] == "password" })
    }

    // ---- alert bridge

    private class CapturingAlerts : OwnerAlerts {
        val emitted = mutableListOf<Triple<AlertKind, String, String>>()
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { emitted += Triple(kind, key, detail) }
    }

    @Test
    fun `throttling and refresh-token reuse raise owner alerts that carry no account data`() {
        val alerts = CapturingAlerts()
        val listener = AlertingAccountEventListener(alerts)
        listener.on(AccountEvent(AccountEventType.LOGIN_THROTTLED, null, h.time.now(), "203.0.113.9", mapOf("scope" to "ip")))
        listener.on(AccountEvent(AccountEventType.REFRESH_REUSE_DETECTED, "acc_1", h.time.now()))
        listener.on(AccountEvent(AccountEventType.LOGIN_SUCCESS, "acc_1", h.time.now()))

        assertEquals(listOf("account.login-throttled", "account.refresh-reuse"), alerts.emitted.map { it.first.name })
        assertTrue(alerts.emitted.none { "@" in it.third })
    }
}
