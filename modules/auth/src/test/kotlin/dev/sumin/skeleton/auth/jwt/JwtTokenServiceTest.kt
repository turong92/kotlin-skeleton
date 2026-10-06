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
    fun `issue and authenticate preserve null optional fields and empty roles`() {
        val service = JwtTokenService(properties, clock)
        val principal = CurrentPrincipal(accountId = "acc_system")

        val authenticated = service.authenticate(service.issue(principal).accessToken)

        assertEquals(principal, authenticated)
    }

    @Test
    fun `authenticate rejects malformed token`() {
        val service = JwtTokenService(properties, clock)

        assertNull(service.authenticate("not-a-jwt"))
    }

    @Test
    fun `authenticate rejects a token whose signature or payload bytes were changed`() {
        val service = JwtTokenService(properties, clock)
        val token = service.issue(CurrentPrincipal(accountId = "acc_user", roles = setOf("USER"))).accessToken
        val (header, payload, signature) = token.split(".")
        // 글자 하나를 바꾸되 base64url 마지막 글자(버려지는 하위 비트가 있어 바이트가 안 바뀔 수 있다)가 아니라 한가운데를 바꾼다
        fun flipMiddle(part: String): String {
            val mid = part.length / 2
            return part.substring(0, mid) + (if (part[mid] == 'A') 'B' else 'A') + part.substring(mid + 1)
        }

        assertNull(service.authenticate("$header.$payload.${flipMiddle(signature)}"))
        assertNull(service.authenticate("$header.${flipMiddle(payload)}.$signature"))
    }

    @Test
    fun `session id survives the token round trip`() {
        val service = JwtTokenService(properties, clock)
        val principal = CurrentPrincipal(accountId = "acc_user", roles = setOf("USER"), sessionId = "ses_42")

        assertEquals("ses_42", service.authenticate(service.issue(principal).accessToken)?.sessionId)
    }
}
