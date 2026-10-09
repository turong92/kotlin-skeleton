package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.GatePathRule
import dev.sumin.skeleton.common.web.RequestPaths
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/** 게이트 인터셉터들(이메일 · 아이디)이 나누는 "이 요청을 검사할 것인가" — 한 곳에서 읽어 두 게이트가 어긋나지 않게 */
internal object GateRequests {
    /**
     * 허용 목록 밖의 보호된(`/api` 아래) 경로로 **인증된** 요청이면 그 호출자의 계정 id, 아니면 null (검사하지 않는다).
     * 에러 디스패치 · 비동기 마무리 · CORS preflight · 정적 자원 핸들러(없는 경로의 404) · 익명 요청은 건드리지 않는다. 경로는 [RequestPaths] 로 — 디코드 후 · 세그먼트 단위 · 컨텍스트 경로 제외.
     */
    fun callerToCheck(request: HttpServletRequest, handler: Any, open: List<GatePathRule>): String? {
        if (request.dispatcherType == DispatcherType.ERROR || request.dispatcherType == DispatcherType.ASYNC) return null   // 에러 페이지 · 이미 통과한 비동기의 마무리
        if (CorsUtils.isPreFlightRequest(request)) return null
        if (handler is ResourceHttpRequestHandler) return null   // 정적 자원 · 없는 경로(404)
        val path = RequestPaths.withinApplication(request)
        if (!GatePathRule.PROTECTED.matches(path)) return null
        if (open.any { it.matches(request.method, path) }) return null
        val authentication = (request.userPrincipal as? Authentication) ?: SecurityContextHolder.getContext().authentication
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return null
        return authentication.name
    }
}

/** 먼저 도는 게이트(아이디, 순서 -110)가 읽은 계정 한 줄을 요청에 두고 뒤의 게이트(이메일, -100)가 받아 쓴다 — 요청마다 한 번만 읽는다 */
object GateAccount {
    const val ATTRIBUTE = "dev.sumin.skeleton.account.web.GateAccount"
}
