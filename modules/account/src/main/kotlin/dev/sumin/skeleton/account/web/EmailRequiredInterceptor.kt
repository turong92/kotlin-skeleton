package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.AccountRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.util.AntPathMatcher
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * `skeleton.account.email.required=true` 일 때, 주소가 아직 없는 계정으로 인증된 요청을 허용 목록 밖에서 403 `ACCOUNT.EMAIL_REQUIRED` 로 막는다.
 * 다른 모듈(board · notes · storage …)의 엔드포인트도 같이 막힌다 — 이 모듈은 그 모듈들을 모르고 **경로**로만 지킨다 (`legal` 의 재동의 필터와 같은 방식, 순서는 이쪽이 먼저).
 * 익명 요청은 건드리지 않는다(보안 체인이 401 을 낸다). 요청마다 계정 한 줄 읽기. 경로는 `skeleton.account.email.include-paths` / `exclude-paths`.
 */
class EmailRequiredInterceptor(
    private val config: AccountProperties.Email,
    private val accounts: () -> AccountRepository,
) : HandlerInterceptor {
    private val matcher = AntPathMatcher()

    /** `METHOD 패턴` 또는 `패턴` */
    private data class Rule(val method: String?, val pattern: String)

    private val excluded = config.excludePaths.map { entry ->
        val parts = entry.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[1].startsWith("/")) Rule(parts[0].uppercase(), parts[1]) else Rule(null, parts[0])
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod) return true   // 없는 경로(404) · 정적 자원은 지키지 않는다 — 실제 엔드포인트만
        val path = request.requestURI.removePrefix(request.contextPath)
        if (config.includePaths.none { matcher.match(it, path) }) return true
        if (excluded.any { (it.method == null || it.method == request.method.uppercase()) && matcher.match(it.pattern, path) }) return true
        val authentication = (request.userPrincipal as? Authentication) ?: SecurityContextHolder.getContext().authentication
        if (authentication == null || authentication is org.springframework.security.authentication.AnonymousAuthenticationToken || !authentication.isAuthenticated) return true
        val account = accounts().findById(authentication.name) ?: return true
        if (account.email == null) throw AccountException(AccountErrorCode.EMAIL_REQUIRED)
        return true
    }
}
