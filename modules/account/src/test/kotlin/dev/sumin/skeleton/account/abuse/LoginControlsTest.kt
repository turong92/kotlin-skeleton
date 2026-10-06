package dev.sumin.skeleton.account.abuse

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountProperties
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
