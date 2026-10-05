package dev.sumin.skeleton.auth.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.auth")
data class AuthProperties(
    val jwt: Jwt = Jwt(),
    val devLogin: DevLogin = DevLogin(),
    val breakGlass: BreakGlass = BreakGlass(),
    /** 안전하지 않은 인증 구성(기본 JWT 비밀 · 시드 계정 저장소 · dev-login)을 거부하는 프로필. */
    val protectedProfiles: List<String> = listOf("prod", "staging"),
) {
    data class Jwt(
        val issuer: String = "kotlin-skeleton",
        val secret: String = DEFAULT_SECRET,
        val accessTokenTtl: Duration = Duration.ofMinutes(15),
    ) {
        companion object {
            /** 개발용 기본값. 보호 프로필(prod · staging)에서는 이 값으로 기동하지 않는다 (AuthStartupValidator). */
            const val DEFAULT_SECRET = "dev-local-jwt-secret-change-me-32-bytes"
        }
    }

    data class DevLogin(
        val enabled: Boolean = false,
    )

    data class BreakGlass(
        val enabled: Boolean = false,
        val secret: String = "",
        val allowedAccountIds: List<String> = emptyList(),
    )
}
