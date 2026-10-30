package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

open class AuthPrincipalAuthentication(
    private val currentPrincipal: CurrentPrincipal,
) : AbstractAuthenticationToken(currentPrincipal.roles.toAuthorities()) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null

    override fun getPrincipal(): CurrentPrincipal = currentPrincipal

    // 다른 모듈이 auth 에 의존하지 않고 호출자를 가리키는 값 — 계정 id (기본 구현은 toString 이다)
    override fun getName(): String = currentPrincipal.accountId
}

private fun Set<String>.toAuthorities(): Collection<GrantedAuthority> =
    map { role ->
        SimpleGrantedAuthority(if (role.startsWith("ROLE_")) role else "ROLE_$role")
    }

/**
 * break-glass 필터만 다는 표지 - 비상문으로 들어온 요청이다. 「사람에게만 거는 문」 가운데 비상문이 목적이라 걸지 않는 것(2단계 인증 등록 필수)이 이것을 읽어 건너뛴다.
 * 보통 로그인 · 키 요청은 이것을 얻을 수 없다(만드는 곳이 [BreakGlassAuthenticationFilter] 하나다).
 */
class BreakGlassAuthentication(principal: CurrentPrincipal) : AuthPrincipalAuthentication(principal)
