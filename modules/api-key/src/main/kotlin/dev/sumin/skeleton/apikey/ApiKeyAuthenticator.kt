package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.security.BearerTokenAuthenticator
import dev.sumin.skeleton.auth.security.BearerTokenRejectedException
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import java.time.Duration
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.Authentication

/**
 * 키로 인증된 요청 - 주체는 **주인 계정에 역할을 비운 것**이다(`Authentication.name` 을 쓰는 기존 컨트롤러가 그대로 돌고, 운영 경로는 역할이 없어 닿지 않는다).
 * [principal] 이 [CurrentPrincipal] 이라 계정 모듈의 호출자 해석도 그대로 읽는다. 어느 키인지 · 범위는 [keyId] · [scopes].
 */
class ApiKeyAuthentication(
    private val currentPrincipal: CurrentPrincipal,
    val keyId: String,
    val scopes: Set<String>,
    val owner: ApiKeyOwner,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): CurrentPrincipal = currentPrincipal

    override fun getName(): String = currentPrincipal.accountId
}

/**
 * `<접두사>_` 로 시작하는 Bearer 토큰을 키로 읽는다([BearerTokenAuthenticator], JWT 보다 먼저 묻는다).
 * 해시로 한 줄 찾고 → 없음 INVALID · 회수 REVOKED · 만료 EXPIRED(키를 가진 사람에게만 보이므로 이유를 알려 준다) → 주인이 막힘(정지 · 탈퇴 대기 · 없어짐) OWNER_BLOCKED(403).
 * 마지막 사용은 1분보다 오래됐을 때만 조건부로 쓴다(쓰기 증폭 방지).
 */
class ApiKeyAuthenticator(
    private val store: ApiKeyStore,
    private val tokens: ApiKeyTokens,
    private val accounts: AuthAccountRepository,
    private val time: TimeProvider,
    private val clientIps: ClientIps,
) : BearerTokenAuthenticator {
    override fun supports(token: String): Boolean = tokens.supports(token)

    override fun authenticate(token: String, request: HttpServletRequest): Authentication {
        if (!tokens.fits(token)) throw reject(ApiKeyErrorCode.INVALID)
        val key = store.findByTokenHash(tokens.hash(token)) ?: throw reject(ApiKeyErrorCode.INVALID)
        val now = time.now()
        when (key.status(now)) {
            ApiKeyStatus.REVOKED -> throw reject(ApiKeyErrorCode.REVOKED)
            ApiKeyStatus.EXPIRED -> throw reject(ApiKeyErrorCode.EXPIRED)
            ApiKeyStatus.ACTIVE -> Unit
        }
        val owner = accounts.findBy(AccountIdentifier(accountId = key.ownerId))
        if (owner == null || owner.loginBlock != null) throw reject(ApiKeyErrorCode.OWNER_BLOCKED)

        store.touchLastUsed(key.id, now, clientIps.of(request).ip, now.minus(LAST_USED_RESOLUTION))
        return ApiKeyAuthentication(CurrentPrincipal(accountId = key.ownerId, roles = emptySet()), key.id, key.scopes, ApiKeyOwner(key.ownerType, key.ownerId))
    }

    private fun reject(code: ApiKeyErrorCode) = BearerTokenRejectedException(code)

    private companion object {
        val LAST_USED_RESOLUTION: Duration = Duration.ofMinutes(1)
    }
}
