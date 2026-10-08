package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.EmailRequiredRule
import dev.sumin.skeleton.common.web.RequestPaths
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.cors.CorsUtils
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/**
 * `skeleton.account.email.required=true` 일 때, 주소가 아직 없는 계정으로 인증된 요청을 허용 목록 밖에서 403 `ACCOUNT.EMAIL_REQUIRED` 로 막는다.
 * 다른 모듈(board · notes · storage …)의 엔드포인트도 같이 막힌다 — 이 모듈은 그 모듈들을 모르고 **경로**로만 지킨다 (`legal` 의 재동의 인터셉터와 같은 방식, 순서는 이쪽이 먼저).
 *
 *  - 경로는 스프링이 핸들러를 고르는 것과 **같은 방식**(디코드 후 · 세그먼트 단위 · 컨텍스트 경로 제외, [RequestPaths])으로 읽는다 — `/%61pi/...` 로 우회하지 못한다.
 *  - `/api/` 아래는 **핸들러 종류와 무관**하게 막는다 (컨트롤러 메서드든 함수형 라우트든). 빠지는 것은 정적 자원 핸들러(없는 경로는 404 그대로) · 에러 디스패치 · CORS preflight 뿐이다.
 *  - HEAD 는 GET 의 규칙을 따른다.
 *  - 익명 요청은 건드리지 않는다(보안 체인이 401 을 낸다). 요청마다 계정 한 줄 읽기.
 * 열어 두는 경로: 코드에 고정된 목록([EmailRequiredRule.BUILT_IN]) + 설정의 `extra-exclude-paths`(더하기만).
 */
class EmailRequiredInterceptor(
    config: AccountProperties.Email,
    private val accounts: () -> AccountRepository,
) : HandlerInterceptor {
    private val open = EmailRequiredRule.BUILT_IN + config.extraExcludePaths.map(EmailRequiredRule::parse)

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.dispatcherType == DispatcherType.ERROR || request.dispatcherType == DispatcherType.ASYNC) return true   // 에러 페이지 · 이미 통과한 비동기의 마무리
        if (CorsUtils.isPreFlightRequest(request)) return true
        if (handler is ResourceHttpRequestHandler) return true   // 정적 자원 · 없는 경로(404)
        val path = RequestPaths.withinApplication(request)
        if (!EmailRequiredRule.PROTECTED.matches(path)) return true
        if (open.any { it.matches(request.method, path) }) return true
        val authentication = (request.userPrincipal as? Authentication) ?: SecurityContextHolder.getContext().authentication
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated) return true
        val account = accounts().findById(authentication.name) ?: return true
        if (account.email == null) throw AccountException(AccountErrorCode.EMAIL_REQUIRED)
        return true
    }
}
