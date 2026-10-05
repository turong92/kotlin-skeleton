package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardCaller
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** `Authentication` → [BoardCaller]. 호출자 id 는 `Authentication.name` (auth 모듈에서는 계정 id), 운영자는 `ROLE_<moderator-role>` 권한이다. */
class BoardCallers(private val properties: BoardProperties) {
    fun resolve(authentication: Authentication?): BoardCaller? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        val role = properties.moderatorRole
        val moderator = authentication.authorities.any { it.authority == "ROLE_$role" || it.authority == role }
        return BoardCaller(authentication.name, moderator)
    }

    /** 쓰기 — 로그인이 필요하다 */
    fun require(authentication: Authentication?): BoardCaller = resolve(authentication) ?: throw unauthorized()

    /** 읽기 — 익명 읽기를 켰으면 null(익명) 도 허용 */
    fun reader(authentication: Authentication?): BoardCaller? =
        resolve(authentication) ?: if (properties.http.allowAnonymousRead) null else throw unauthorized()

    private fun unauthorized() = ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
