package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardCaller
import dev.sumin.skeleton.board.BoardPermissions
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.GuestContext
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication

/** `Authentication` → [BoardCaller]. 호출자 id 는 `Authentication.name` (auth 모듈에서는 계정 id), 운영 권한(`board.moderate` · `board.manage`)은 platform 의 [OperatorAccess] 가 로그인한 호출마다 판정한다 — 익명 호출은 묻지 않는다. */
class BoardCallers(private val properties: BoardProperties, private val access: OperatorAccess, private val clientIps: ClientIps = ClientIps()) {
    /** 쓰기 요청의 쓴 사람 — 로그인했으면 [caller], 아니고 `guest.enabled` 면 비회원 맥락 [guest]. 둘 다 없으면 401 */
    data class Writer(val caller: BoardCaller?, val guest: GuestContext?)

    fun writer(authentication: Authentication?, http: HttpServletRequest, captchaToken: String? = null): Writer {
        resolve(authentication)?.let { return Writer(it, null) }
        if (!properties.guest.enabled) throw unauthorized()
        val address = clientIps.of(http)
        return Writer(null, GuestContext(http.getHeader(GUEST_SECRET_HEADER)?.takeIf { it.isNotEmpty() }, address.limitKey, address.ip, captchaToken))
    }

    fun resolve(authentication: Authentication?): BoardCaller? {
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        val roles = authentication.authorities.mapNotNull { it.authority?.removePrefix("ROLE_") }.toSet()
        val ref = OperatorRef(authentication.name, roles)
        return BoardCaller(authentication.name, access.has(ref, BoardPermissions.MODERATE), access.has(ref, BoardPermissions.MANAGE))
    }

    /** 쓰기 — 로그인이 필요하다 */
    fun require(authentication: Authentication?): BoardCaller = resolve(authentication) ?: throw unauthorized()

    /** 읽기 — 익명 읽기를 켰으면 null(익명) 도 허용 */
    fun reader(authentication: Authentication?): BoardCaller? =
        resolve(authentication) ?: if (properties.http.allowAnonymousRead) null else throw unauthorized()

    companion object {
        /** 비회원의 비밀값 — 헤더로만 받는다 (본문 · 쿼리 · 쿠키 아님) */
        const val GUEST_SECRET_HEADER = "X-Board-Guest-Secret"
    }

    private fun unauthorized() = ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)
}
