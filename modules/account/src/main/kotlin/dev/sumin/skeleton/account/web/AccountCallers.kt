package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 호출자 — 계정 id 는 `Authentication.name`, 현재 세션은 JWT 의 `sid`(있으면). 로그인하지 않았으면 401 */
data class AccountCaller(val accountId: String, val sessionId: String?, val roles: Set<String>)

class AccountCallers(private val admin: AccountProperties.Admin) {
    fun require(authentication: Authentication?): AccountCaller {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val principal = authentication.principal as? CurrentPrincipal
        val roles = authentication.authorities.map { (it.authority ?: "").removePrefix("ROLE_") }.toSet()
        return AccountCaller(authentication.name, principal?.sessionId, roles)
    }

    /** 관리자 역할이 없으면 403 (로그인하지 않았으면 401) */
    fun requireAdmin(authentication: Authentication?): AccountCaller {
        val caller = require(authentication)
        if (admin.role !in caller.roles) throw ApplicationException("Administrator role required", PlatformErrorCode.FORBIDDEN)
        return caller
    }
}

internal fun clientIp(request: HttpServletRequest, clientIps: dev.sumin.skeleton.common.web.ClientIps): String = clientIps.of(request).ip
