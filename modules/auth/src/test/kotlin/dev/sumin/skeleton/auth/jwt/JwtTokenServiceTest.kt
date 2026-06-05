package dev.sumin.skeleton.auth.jwt

import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtTokenServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC)
    private val properties = AuthProperties.Jwt(
        issuer = "test-issuer",
        secret = "test-jwt-secret-change-me-32-bytes",
        accessTokenTtl = Duration.ofMinutes(15),
    )

    @Test
    fun `issue creates an access token with expected expiration`() {
        val service = JwtTokenService(properties, clock)
        val principal = CurrentPrincipal(
            accountId = "acc_user",
            username = "user",
            email = "user@example.com",
            roles = setOf("USER"),
        )

        val issuedToken = service.issue(principal)

        assertNotNull(issuedToken.accessToken)
        assertEquals(Instant.parse("2026-06-05T12:15:00Z"), issuedToken.expiresAt)
    }

    @Test
    fun `authenticate recovers issued principal fields`() {
        val service = JwtTokenService(properties, clock)
        val principal = CurrentPrincipal(
            accountId = "acc_user",
            username = "user",
            email = "user@example.com",
            roles = setOf("USER"),
        )

        val authenticated = service.authenticate(service.issue(principal).accessToken)

        assertEquals(principal, authenticated)
    }

    @Test
    fun `authenticate preserves multiple roles`() {
        val service = JwtTokenService(properties, clock)
        val principal = CurrentPrincipal(
            accountId = "acc_admin",
            username = "admin",
            email = "admin@example.com",
            roles = setOf("USER", "ADMIN"),
        )

        val authenticated = service.authenticate(service.issue(principal).accessToken)

        assertEquals(setOf("USER", "ADMIN"), authenticated?.roles)
    }

    @Test
    fun `authenticate rejects malformed token`() {
        val service = JwtTokenService(properties, clock)

        assertNull(service.authenticate("not-a-jwt"))
    }
}
