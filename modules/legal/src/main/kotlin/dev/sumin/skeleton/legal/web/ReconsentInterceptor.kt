package dev.sumin.skeleton.legal.web

import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.LegalErrorCode
import dev.sumin.skeleton.legal.consentRequired
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor

/**
 * 필수 문서에 동의가 모자란(처음이거나 새 판이 유예를 넘긴) 로그인 호출자의 보호된 요청을 403 `LEGAL.RECONSENT_REQUIRED` 로 막는다 — 동의 화면을 거치게.
 * 경로는 `skeleton.legal.reconsent.include-paths` / `exclude-paths`. 익명 요청은 건드리지 않는다(보안 체인이 401 을 낸다). 요청마다 색인 있는 읽기 한 번.
 */
class ReconsentInterceptor(private val service: ConsentService, private val callers: LegalCallers) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val authentication = (request.userPrincipal as? Authentication) ?: SecurityContextHolder.getContext().authentication
        val subject = callers.resolve(authentication) ?: return true
        val missing = service.requireCurrent(subject)
        if (missing.isNotEmpty()) throw consentRequired(missing, LegalErrorCode.RECONSENT_REQUIRED)
        return true
    }
}
