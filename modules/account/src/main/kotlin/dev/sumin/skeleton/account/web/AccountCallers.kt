package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** 호출자 — 계정 id 는 `Authentication.name`, 현재 세션은 JWT 의 `sid`(있으면). 로그인하지 않았으면 401 */
data class AccountCaller(val accountId: String, val sessionId: String?, val roles: Set<String>)

class AccountCallers(private val access: () -> OperatorAccess) {
    fun require(authentication: Authentication?): AccountCaller {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) {
            throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
        }
        val principal = authentication.principal as? CurrentPrincipal
        val roles = authentication.authorities.map { (it.authority ?: "").removePrefix("ROLE_") }.toSet()
        return AccountCaller(authentication.name, principal?.sessionId, roles)
    }

    /**
     * 권한이 없으면 403 `PLATFORM.FORBIDDEN` (로그인하지 않았으면 401). 토큰이 들고 온 역할은 **힌트일 뿐**이고 권한은 공용 검사기가 정한다 —
     * `account` 가 있으면 지금 저장소의 계정이므로, 정지되거나 역할을 회수당한 운영자가 15분짜리 액세스 토큰으로 자기 조치를 되돌리지 못한다.
     */
    fun requirePermission(authentication: Authentication?, permission: String): AccountCaller {
        val caller = require(authentication)
        access().require(ref(caller), permission)
        return caller
    }

    /** 이 호출자의 권한 전부 — 역할 부여의 권한 상승 판정에 쓴다 */
    fun permissionsOf(caller: AccountCaller): Set<String> = access().permissionsOf(ref(caller))

    private fun ref(caller: AccountCaller) = OperatorRef(caller.accountId, caller.roles)
}
