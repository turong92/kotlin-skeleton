package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 호출자 — 계정 id 는 `Authentication.name`, 현재 세션은 JWT 의 `sid`(있으면). 로그인하지 않았으면 401 */
data class AccountCaller(val accountId: String, val sessionId: String?, val roles: Set<String>)

class AccountCallers(private val admin: AccountProperties.Admin, private val accounts: () -> AccountRepository) {
    fun require(authentication: Authentication?): AccountCaller {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val principal = authentication.principal as? CurrentPrincipal
        val roles = authentication.authorities.map { (it.authority ?: "").removePrefix("ROLE_") }.toSet()
        return AccountCaller(authentication.name, principal?.sessionId, roles)
    }

    /**
     * 관리자 역할이 없으면 403 (로그인하지 않았으면 401). 토큰이 들고 온 역할은 **힌트일 뿐**이고 권한은 지금 저장소의 계정이 정한다 —
     * 정지되거나 ADMIN 을 회수당한 관리자가 15분짜리 액세스 토큰으로 자기 조치를 되돌리지 못하게.
     */
    fun requireAdmin(authentication: Authentication?): AccountCaller {
        val caller = require(authentication)
        if (admin.role !in caller.roles) throw forbidden()
        val stored = accounts().findById(caller.accountId)
        if (stored == null || stored.status != AccountStatus.ACTIVE || admin.role !in stored.roles) throw forbidden()
        return caller.copy(roles = stored.roles)
    }

    private fun forbidden() = ApplicationException("Administrator role required", PlatformErrorCode.FORBIDDEN)
}

