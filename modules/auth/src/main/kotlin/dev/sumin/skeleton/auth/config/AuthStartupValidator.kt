package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.SeedAuthAccountRepository

/**
 * 보호 프로필(기본 prod · staging, `skeleton.auth.protected-profiles`)에서 안전하지 않은 인증 구성이면 기동을 막는다.
 * local · dev · test · 프로필 없음은 그대로 동작한다 (스타터의 테스트가 시드 사용자에 기댄다).
 */
object AuthStartupValidator {
    /** HS256 은 256비트(32바이트) 이상의 키를 요구한다 (RFC 7518 §3.2). */
    const val MIN_JWT_SECRET_BYTES = 32

    fun validate(properties: AuthProperties, activeProfiles: Set<String>, accountRepository: AuthAccountRepository? = null) {
        val protectedProfileActive = isProtected(properties, activeProfiles)

        if (properties.devLogin.enabled && protectedProfileActive) {
            throw IllegalStateException("Dev login cannot be enabled in prod or staging")
        }

        validateJwtSecret(properties, activeProfiles)

        if (protectedProfileActive && accountRepository is SeedAuthAccountRepository) {
            throw IllegalStateException(
                "The built-in in-memory AuthAccountRepository seeds user/password and admin/password accounts and " +
                    "cannot be used with the active protected profile(s) ${protectedActive(properties, activeProfiles)}. " +
                    "Register your own AuthAccountRepository bean.",
            )
        }

        if (!properties.breakGlass.enabled) {
            return
        }

        if (properties.breakGlass.secret.isBlank()) {
            throw IllegalStateException("Break-glass secret is required when break-glass login is enabled")
        }

        if (protectedProfileActive && properties.breakGlass.allowedAccountIds.none { it.isNotBlank() }) {
            throw IllegalStateException("Break-glass allowed account ids are required in prod or staging")
        }
    }

    /** JWT 비밀만 따로 본다 — `JwtTokenService` 를 만들기 전에 이름 붙은 메시지로 막으려고 AutoConfiguration 이 먼저 부른다. */
    fun validateJwtSecret(properties: AuthProperties, activeProfiles: Set<String>) {
        if (!isProtected(properties, activeProfiles)) {
            return
        }

        val secret = properties.jwt.secret
        val profiles = protectedActive(properties, activeProfiles)
        val problem = when {
            secret.isBlank() -> "is blank"
            secret == AuthProperties.Jwt.DEFAULT_SECRET -> "is the built-in development default"
            secret.toByteArray(Charsets.UTF_8).size < MIN_JWT_SECRET_BYTES -> "is shorter than $MIN_JWT_SECRET_BYTES bytes (required by HS256)"
            else -> return
        }
        throw IllegalStateException(
            "skeleton.auth.jwt.secret $problem, which is not allowed with the active protected profile(s) $profiles. " +
                "Set skeleton.auth.jwt.secret (env JWT_SECRET) to a random secret of at least $MIN_JWT_SECRET_BYTES bytes.",
        )
    }

    private fun isProtected(properties: AuthProperties, activeProfiles: Set<String>): Boolean =
        protectedActive(properties, activeProfiles).isNotEmpty()

    private fun protectedActive(properties: AuthProperties, activeProfiles: Set<String>): List<String> =
        activeProfiles.filter { it in properties.protectedProfiles }.sorted()
}
