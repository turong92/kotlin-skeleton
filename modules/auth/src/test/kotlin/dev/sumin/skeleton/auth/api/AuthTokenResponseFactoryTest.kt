package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthTokenResponseFactoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC)
    private val jwt = JwtTokenService(
        AuthProperties.Jwt(
            issuer = "test-issuer",
            secret = "test-jwt-secret-change-me-32-bytes",
            accessTokenTtl = Duration.ofMinutes(15),
        ),
        clock,
    )

    @Test
    fun `issue creates bearer token response from auth account`() {
        val factory = AuthTokenResponseFactory(jwt)
        val account = AuthAccount(
            accountId = "acc_user",
            username = "user",
            email = "user@example.com",
            passwordHash = "hash",
            roles = setOf("USER"),
        )

        val response = factory.issue(account)

        assertNotNull(response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(Instant.parse("2026-06-05T12:15:00Z"), response.expiresAt)
        assertEquals("acc_user", response.principal.accountId)
        assertEquals("user", response.principal.username)
        assertEquals("user@example.com", response.principal.email)
        assertEquals(setOf("USER"), response.principal.roles)
    }
}
