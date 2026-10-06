package dev.sumin.skeleton.auth.login

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AccountBlockedException
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.api.InvalidCredentialsException
import dev.sumin.skeleton.auth.api.PasswordLoginRequest
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 비밀번호 로그인 한 건. 계정이 없거나 비밀번호가 없는 계정이어도 **해시 비교를 한 번** 한다 — 응답 시간으로 계정 존재 여부가 드러나지 않게.
 * 맞는 비밀번호로 들어왔고 인코더가 더 새 방식을 원하면([PasswordEncoder.upgradeEncoding]) 그 자리에서 다시 해시해 저장소에 돌려준다.
 */
class PasswordLoginService(
    private val accounts: AuthAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val tokenResponses: AuthTokenResponseFactory,
    private val hooks: () -> List<LoginHooks> = { emptyList() },
) {
    private val dummyHash: String by lazy { requireNotNull(passwordEncoder.encode("skeleton-timing-equalizer")) }

    fun login(request: PasswordLoginRequest, clientIp: String?): AuthTokenResponse {
        val identifier = try {
            AccountIdentifier.from(request.accountId, request.username, request.email)
        } catch (_: IllegalArgumentException) {
            throw InvalidCredentialsException()
        }
        val attempt = LoginAttempt(key(identifier), clientIp)
        val active = hooks()
        active.forEach { it.beforeAttempt(attempt) }

        val account = accounts.findBy(identifier)
        val password = request.password?.takeIf { it.isNotEmpty() }
        val matched = when {
            password == null -> false
            account != null && account.passwordHash.isNotBlank() -> safeMatches(password, account.passwordHash)
            else -> { safeMatches(password, dummyHash); false }
        }
        if (!matched || account == null) {
            active.forEach { it.onFailure(attempt, if (account == null) LoginFailure.UNKNOWN_ACCOUNT else LoginFailure.BAD_PASSWORD, account) }
            throw InvalidCredentialsException()
        }

        val response = try {
            tokenResponses.issue(account)
        } catch (blocked: AccountBlockedException) {
            active.forEach { it.onFailure(attempt, LoginFailure.BLOCKED, account) }
            throw blocked
        }
        if (accounts.storesUpgradedPasswordHash && passwordEncoder.upgradeEncoding(account.passwordHash)) {
            passwordEncoder.encode(password)?.let { accounts.upgradePasswordHash(account.accountId, it) }
        }
        active.forEach { it.onSuccess(attempt, account) }
        return response
    }

    /** bcrypt 는 72바이트를 넘는 입력을 거부하기도 한다 — 그건 서버 오류가 아니라 틀린 비밀번호다 */
    private fun safeMatches(raw: String, hash: String): Boolean =
        try { passwordEncoder.matches(raw, hash) } catch (_: IllegalArgumentException) { false }

    private fun key(id: AccountIdentifier): String = when {
        id.accountId != null -> "accountId:${id.accountId}"
        id.email != null -> "email:${id.email}"
        else -> "username:${id.username}"
    }
}
