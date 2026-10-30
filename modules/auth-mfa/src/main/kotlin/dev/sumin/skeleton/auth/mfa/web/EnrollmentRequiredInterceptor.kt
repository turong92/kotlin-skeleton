package dev.sumin.skeleton.auth.mfa.web

import dev.sumin.skeleton.account.GatePathRule
import dev.sumin.skeleton.auth.mfa.MfaErrorCode
import dev.sumin.skeleton.auth.mfa.MfaException
import dev.sumin.skeleton.auth.mfa.MfaRequirement
import dev.sumin.skeleton.auth.security.BreakGlassAuthentication
import dev.sumin.skeleton.auth.security.NonInteractiveAuthentication
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
 * 2단계가 **필수**(`required-for`)인데 아직 등록 전인 계정의 요청을 허용 목록 밖에서 403 `MFA.ENROLLMENT_REQUIRED` 로 막는다 -
 * 로그인은 되고(그래야 등록할 수 있다) 나머지 `/api` 아래가 잠긴다. 다른 모듈의 엔드포인트도 **경로**로 같이 막힌다(이메일 필수 문과 같은 방식 · 같은 경로 규칙 [GatePathRule]).
 *
 * 열어 두는 길: `/api/v1/auth` 아래 전부(등록 · 로그인 · 로그아웃) · `<base-path>` 아래 전부 · `GET /api/v1/account/me` · `POST /api/v1/account/reauth/confirmation`(메일 코드 다시 인증) + 설정의 `required.extra-exclude-paths`(더하기만).
 * 건드리지 않는 것: 익명(보안 체인이 401) · 에러 디스패치 · CORS preflight · 정적 자원 · **[NonInteractiveAuthentication]**(API 키 같은 프로그램 자격 - 키는 사람이 아니라 등록할 수 없다) · **[BreakGlassAuthentication]**(비상문 - 비상 때 열려야 하므로 2단계를 걸지 않는다. 대상 계정이 운영자여도 등록 전에 열린다).
 */
class EnrollmentRequiredInterceptor(
    private val requirement: MfaRequirement,
    basePath: String,
    extraExcludePaths: List<String>,
) : HandlerInterceptor {
    private val open: List<GatePathRule> =
        (listOf("/api/v1/auth/**", "$basePath/**", "GET /api/v1/account/me", "POST /api/v1/account/reauth/confirmation") + extraExcludePaths)
            .map { GatePathRule.parse(it, SETTING) }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.dispatcherType == DispatcherType.ERROR || request.dispatcherType == DispatcherType.ASYNC) return true
        if (CorsUtils.isPreFlightRequest(request) || handler is ResourceHttpRequestHandler) return true
        val path = RequestPaths.withinApplication(request)
        if (!GatePathRule.PROTECTED.matches(path) || open.any { it.matches(request.method, path) }) return true
        val authentication = (request.userPrincipal as? Authentication) ?: SecurityContextHolder.getContext().authentication
        if (authentication == null || authentication is AnonymousAuthenticationToken || !authentication.isAuthenticated || authentication is NonInteractiveAuthentication || authentication is BreakGlassAuthentication) return true
        if (requirement.enrollmentMissing(authentication.name)) throw MfaException(MfaErrorCode.ENROLLMENT_REQUIRED)
        return true
    }

    private companion object {
        const val SETTING = "skeleton.auth-mfa.required.extra-exclude-paths"
    }
}
