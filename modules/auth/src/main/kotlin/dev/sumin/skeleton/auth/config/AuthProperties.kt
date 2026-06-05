package dev.sumin.skeleton.auth.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.auth")
data class AuthProperties(
    val jwt: Jwt = Jwt(),
    val devLogin: DevLogin = DevLogin(),
    val breakGlass: BreakGlass = BreakGlass(),
) {
    data class Jwt(
        val issuer: String = "kotlin-skeleton",
        val secret: String = "dev-local-jwt-secret-change-me-32-bytes",
        val accessTokenTtl: Duration = Duration.ofMinutes(15),
    )

    data class DevLogin(
        val enabled: Boolean = false,
    )

    data class BreakGlass(
        val enabled: Boolean = false,
        val secret: String = "",
        val allowedAccountIds: List<String> = emptyList(),
    )
}
