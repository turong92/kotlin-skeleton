package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.legal.Subject
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** `Authentication` → [Subject]. 주체 id 는 `Authentication.name`(auth 모듈에서는 계정 id), 운영자 판정은 platform 의 [OperatorAccess] 다 */
class LegalCallers(private val access: OperatorAccess) {
    fun resolve(authentication: Authentication?): Subject? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return Subject.account(authentication.name)
    }

    fun require(authentication: Authentication?): Subject = resolve(authentication) ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)

    /** 로그인 필요(401), [permission] 이 없으면 403 — 매 호출 [OperatorAccess] 가 판정한다 */
    fun requireOperator(authentication: Authentication?, permission: String) {
        require(authentication)
        val roles = authentication!!.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        if (!access.has(OperatorRef(authentication.name, roles), permission)) throw ApplicationException("Missing permission: $permission", PlatformErrorCode.FORBIDDEN)
    }
}
