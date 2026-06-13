package dev.sumin.skeleton.api

import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkeletonNotificationWebSocketJwtConfigurationTest {
    private val jwtTokenService = JwtTokenService(
        AuthProperties.Jwt(
            issuer = "test-issuer",
            secret = "test-jwt-secret-change-me-32-bytes",
            accessTokenTtl = Duration.ofMinutes(15),
        ),
    )

    @Test
    fun `verifier maps JWT subject to websocket principal name`() {
        val configuration = SkeletonNotificationWebSocketJwtConfiguration()
        val verifier = configuration.notificationWebSocketTokenVerifier(jwtTokenService)
        val token = jwtTokenService.issue(
            CurrentPrincipal(
                accountId = "acc_user",
                username = "user",
                email = "user@example.com",
                roles = setOf("USER"),
            ),
        ).accessToken

        val principal = verifier.verify(token)

        assertEquals("acc_user", principal?.name)
    }

    @Test
    fun `verifier rejects invalid JWT`() {
        val configuration = SkeletonNotificationWebSocketJwtConfiguration()
        val verifier = configuration.notificationWebSocketTokenVerifier(jwtTokenService)

        assertNull(verifier.verify("invalid-token"))
    }
}
