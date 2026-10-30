package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.session.LoginSessionIssuer
import dev.sumin.skeleton.auth.session.OpenedSession
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/** 모든 로그인이 지나는 `issue` 한 곳의 두 번째 단계 고리 — 비면 지금 그대로, 차면 토큰 · 세션 없이 403 `AUTH.MFA_REQUIRED` */
class SecondFactorGateTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC)
    private val jwt = JwtTokenService(AuthProperties.Jwt(issuer = "test-issuer", secret = "test-jwt-secret-change-me-32-bytes"), clock)
    private val account = AuthAccount("acc_1", "ann", "ann@example.com", "x", setOf("USER"))
    private val refreshAt = Instant.parse("2030-01-01T00:00:00Z")
    private val expiresAt = Instant.parse("2026-06-05T12:05:00Z")
    private val secret = "mfa-token-original-value-never-logged"

    private class Sessions(private val refreshAt: Instant) : LoginSessionIssuer {
        var opened = 0
        override fun open(account: AuthAccount) = OpenedSession("ses_${++opened}", "r1.token", refreshAt)
    }
    private val issuer = Sessions(refreshAt)

    private class Gate(private val answer: (AuthAccount) -> SecondFactorChallenge?) : SecondFactorGate {
        var asked = 0
        override fun challenge(account: AuthAccount): SecondFactorChallenge? { asked++; return answer(account) }
    }

    private fun challenging() = Gate { SecondFactorChallenge(secret, listOf("totp", "recovery_code"), expiresAt) }
    private fun factory(vararg gates: SecondFactorGate) = AuthTokenResponseFactory(jwt, secondFactorGates = { gates.toList() }, sessions = { issuer })
    private val json = tools.jackson.module.kotlin.jacksonMapperBuilder().build()

    @Test
    fun `gates that do not challenge change nothing - the response is byte for byte the one without a gate`() {
        val none = json.writeValueAsString(AuthTokenResponseFactory(jwt, sessions = { Sessions(refreshAt) }).issue(account))
        val quiet = Gate { null }
        val gated = json.writeValueAsString(AuthTokenResponseFactory(jwt, secondFactorGates = { listOf(quiet) }, sessions = { Sessions(refreshAt) }).issue(account))
        assertEquals(1, quiet.asked)
        assertEquals(none, gated)
    }

    @Test
    fun `a challenge turns the login into 403 AUTH_MFA_REQUIRED with the mfaToken and no token or session`() {
        val ex = assertFailsWith<SecondFactorRequiredException> { factory(challenging()).issue(account) }
        assertEquals("AUTH.MFA_REQUIRED", ex.errorCode.code)
        assertEquals(403, ex.status.value())
        assertEquals(mapOf("mfaToken" to secret, "methods" to listOf("totp", "recovery_code"), "expiresAt" to expiresAt), ex.data)
        assertEquals(0, issuer.opened, "no session is opened before the second step")
        assertFalse(secret in ex.message.orEmpty() || secret in ex.toString(), "the mfaToken is not part of the message that logs print")
    }

    @Test
    fun `the first gate that challenges wins and later ones are not asked`() {
        val first = challenging()
        val second = Gate { SecondFactorChallenge("other", listOf("passkey"), expiresAt) }
        assertFailsWith<SecondFactorRequiredException> { factory(Gate { null }, first, second).issue(account) }
        assertEquals(0, second.asked)
    }

    @Test
    fun `a blocked account is refused as before and the gate is not asked`() {
        val gate = challenging()
        val blocked = account.copy(loginBlock = LoginBlock.SUSPENDED)
        assertFailsWith<AccountBlockedException> { factory(gate).issue(blocked) }
        assertEquals(0, gate.asked)
    }

    @Test
    fun `issueAfterSecondFactor opens the session and tokens without asking the gate again`() {
        val gate = challenging()
        val response = factory(gate).issueAfterSecondFactor(account)
        assertEquals("ses_1", response.sessionId)
        assertNotNull(response.refreshToken)
        assertEquals(0, gate.asked)
    }

    @Test
    fun `issueAfterSecondFactor still refuses a blocked account`() {
        assertFailsWith<AccountBlockedException> { factory(challenging()).issueAfterSecondFactor(account.copy(loginBlock = LoginBlock.DELETION_PENDING)) }
        assertEquals(0, issuer.opened)
    }

    @Test
    fun `refresh keeps working - issueWith never asks the gate`() {
        val gate = challenging()
        val response = factory(gate).issueWith(account, OpenedSession("ses_9", "r1.next", refreshAt))
        assertEquals("ses_9", response.sessionId)
        assertEquals(0, gate.asked)
    }
}
