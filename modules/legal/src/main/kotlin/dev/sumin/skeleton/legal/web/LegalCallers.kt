package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.legal.Subject
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** `Authentication` → [Subject]. 주체 id 는 `Authentication.name`(auth 모듈에서는 계정 id), 운영자는 `ROLE_<admin-role>` 권한이다 */
class LegalCallers(private val adminRole: String) {
    fun resolve(authentication: Authentication?): Subject? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return Subject.account(authentication.name)
    }

    fun require(authentication: Authentication?): Subject = resolve(authentication) ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)

    fun requireAdmin(authentication: Authentication?) {
        require(authentication)
        if (authentication!!.authorities.none { it.authority == "ROLE_$adminRole" || it.authority == adminRole }) {
            throw ApplicationException("Admin role required", PlatformErrorCode.FORBIDDEN)
        }
    }
}
