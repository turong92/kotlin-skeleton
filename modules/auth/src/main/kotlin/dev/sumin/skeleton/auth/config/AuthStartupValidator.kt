package dev.sumin.skeleton.auth.config

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.SeedAuthAccountRepository
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployFinding
import dev.sumin.skeleton.common.deploy.DeployGuardViolationException

/**
 * 보호 프로필(기본 prod · staging, `skeleton.auth.protected-profiles`)에서 안전하지 않은 인증 구성이면 기동을 막는다.
 * local · dev · test · 프로필 없음은 그대로 동작한다 (스타터의 테스트가 시드 사용자에 기댄다).
 * `skeleton.env` 를 stage · prod 로 정했다면 프로필과 상관없이 같은 규칙이 선다 (옵트인 — 정하지 않으면 이전과 같다).
 *
 * 규칙은 [problems] 한 곳에 있고, [AuthDeployGuard] 가 그것을 DeployGuard 로 내놓는다. 던지는 예외는 [DeployGuardViolationException]
 * (IllegalStateException) — 문제가 하나면 메시지는 예전과 같다.
 */
object AuthStartupValidator {
    /** HS256 은 256비트(32바이트) 이상의 키를 요구한다 (RFC 7518 §3.2). */
    const val MIN_JWT_SECRET_BYTES = 32

    private const val GUARD = "auth"

    fun validate(properties: AuthProperties, activeProfiles: Set<String>, accountRepository: AuthAccountRepository? = null) =
        validate(properties, DeployContext(null, activeProfiles), accountRepository)

    fun validate(properties: AuthProperties, context: DeployContext, accountRepository: AuthAccountRepository? = null) {
        val problems = problems(properties, context, accountRepository)
        if (problems.isNotEmpty()) throw DeployGuardViolationException(problems.map { DeployFinding(GUARD, it) })
    }

    /** JWT 비밀만 따로 본다 — `JwtTokenService` 를 만들기 전에 이름 붙은 메시지로 막으려고 AutoConfiguration 이 먼저 부른다. */
    fun validateJwtSecret(properties: AuthProperties, activeProfiles: Set<String>) =
        validateJwtSecret(properties, DeployContext(null, activeProfiles))

    fun validateJwtSecret(properties: AuthProperties, context: DeployContext) {
        val problem = jwtSecretProblem(properties, context) ?: return
        throw DeployGuardViolationException(listOf(DeployFinding(GUARD, problem)))
    }

    /** 이 환경에서 서면 안 되는 인증 구성 전부 (메시지에 비밀 값은 없다 — 속성 · 빈 이름만) */
    fun problems(properties: AuthProperties, context: DeployContext, accountRepository: AuthAccountRepository? = null): List<String> =
        buildList {
            val protectedNow = context.protectedBy(properties.protectedProfiles)
            if (properties.devLogin.enabled && protectedNow) add("Dev login cannot be enabled in prod or staging")
            jwtSecretProblem(properties, context)?.let(::add)
            if (protectedNow && accountRepository is SeedAuthAccountRepository) {
                add(
                    "The built-in in-memory AuthAccountRepository seeds user/password and admin/password accounts and " +
                        "cannot be used with the active protected profile(s) ${context.protectedBecause(properties.protectedProfiles)}. " +
                        "Register your own AuthAccountRepository bean.",
                )
            }
            if (properties.breakGlass.enabled) {
                if (properties.breakGlass.secret.isBlank()) add("Break-glass secret is required when break-glass login is enabled")
                if (protectedNow && properties.breakGlass.allowedAccountIds.none { it.isNotBlank() }) {
                    add("Break-glass allowed account ids are required in prod or staging")
                }
            }
        }

    private fun jwtSecretProblem(properties: AuthProperties, context: DeployContext): String? {
        val secret = properties.jwt.secret
        // 빈 비밀은 어떤 환경에서도 서명기가 "Empty key" 로 죽는다 — 보호 환경이 아니어도 이름 붙은 메시지로 막는다 (배포라면 선언의 secrets 에 JWT_SECRET 이 빠진 것)
        if (secret.isBlank() && !context.protectedBy(properties.protectedProfiles)) {
            return "skeleton.auth.jwt.secret is blank, so no token can be signed. Set skeleton.auth.jwt.secret (env JWT_SECRET) — in a deployment declaration list JWT_SECRET under secrets: so the platform generates it."
        }
        if (!context.protectedBy(properties.protectedProfiles)) return null
        val reason = when {
            secret.isBlank() -> "is blank"
            secret == AuthProperties.Jwt.DEFAULT_SECRET -> "is the built-in development default"
            secret.toByteArray(Charsets.UTF_8).size < MIN_JWT_SECRET_BYTES -> "is shorter than $MIN_JWT_SECRET_BYTES bytes (required by HS256)"
            else -> return null
        }
        return "skeleton.auth.jwt.secret $reason, which is not allowed with the active protected profile(s) " +
            "${context.protectedBecause(properties.protectedProfiles)}. " +
            "Set skeleton.auth.jwt.secret (env JWT_SECRET) to a random secret of at least $MIN_JWT_SECRET_BYTES bytes (deployment: list JWT_SECRET under secrets: in the declaration)."
    }
}
