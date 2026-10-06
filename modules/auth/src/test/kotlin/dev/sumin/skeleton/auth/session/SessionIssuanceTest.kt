package dev.sumin.skeleton.auth.session

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.auth.api.AuthErrorCode
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionIssuanceTest {
    private val jwt = JwtTokenService(AuthProperties.Jwt(secret = "test-jwt-secret-change-me-32-bytes"))
    private val account = AuthAccount("acc_1", "ann", "ann@example.com", "x", setOf("USER"))
    private val refreshAt = Instant.parse("2030-01-01T00:00:00Z")

    private val issuer = object : LoginSessionIssuer {
        var opened = 0
        override fun open(account: AuthAccount) = OpenedSession("ses_${++opened}", "r1.token", refreshAt)
    }

    @Test
    fun `without an issuer the response is exactly what it was`() {
        val response = AuthTokenResponseFactory(jwt).issue(account)
        assertNull(response.refreshToken)
        assertNull(response.sessionId)
        assertNull(response.principal.sessionId)
    }

    @Test
    fun `without an issuer the JSON is byte-for-byte what auth-only apps always sent - no sessionId, no refresh fields`() {
        val json = tools.jackson.module.kotlin.jacksonMapperBuilder().build().writeValueAsString(AuthTokenResponseFactory(jwt).issue(account))
        assertEquals(false, "sessionId" in json || "refreshToken" in json || "refreshExpiresAt" in json, json)
        val principal = tools.jackson.module.kotlin.jacksonMapperBuilder().build().writeValueAsString(dev.sumin.skeleton.auth.principal.CurrentPrincipal("acc_1", "ann", "ann@example.com", setOf("USER")))
        assertEquals("""{"accountId":"acc_1","username":"ann","email":"ann@example.com","roles":["USER"]}""", principal)
    }

    @Test
    fun `an issuer's session id rides in the jwt and the refresh token in the response`() {
        val response = AuthTokenResponseFactory(jwt) { issuer }.issue(account)
        assertEquals("ses_1", response.sessionId)
        assertEquals("r1.token", response.refreshToken)
        assertEquals(refreshAt, response.refreshExpiresAt)
        assertEquals("ses_1", jwt.authenticate(response.accessToken)?.sessionId)
    }

    @Test
    fun `issueWith reuses a session that already exists (refresh path) and opens no new one`() {
        val response = AuthTokenResponseFactory(jwt) { issuer }.issueWith(account, OpenedSession("ses_9", "r1.next", refreshAt))
        assertEquals("ses_9", response.sessionId)
        assertEquals(0, issuer.opened)
    }

    @Test
    fun `a blocked account gets no token and no session`() {
        val factory = AuthTokenResponseFactory(jwt) { issuer }
        val suspended = assertFailsWith<ApplicationException> { factory.issue(account.copy(loginBlock = LoginBlock.SUSPENDED)) }
        assertEquals(AuthErrorCode.ACCOUNT_SUSPENDED, suspended.errorCode)
        val unverified = assertFailsWith<ApplicationException> { factory.issue(account.copy(loginBlock = LoginBlock.EMAIL_NOT_VERIFIED)) }
        assertEquals(AuthErrorCode.EMAIL_NOT_VERIFIED, unverified.errorCode)
        assertEquals(0, issuer.opened)
    }
}
