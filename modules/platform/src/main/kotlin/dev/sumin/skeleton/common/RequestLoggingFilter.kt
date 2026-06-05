package dev.sumin.skeleton.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 모든 HTTP 요청의 시작/종료를 각각 한 줄씩 로깅.
 *
 * - traceId 는 [TraceIdFilter] 가 먼저 MDC에 주입 → 로그 correlation 패턴 `[traceId]` 에 자동 출력
 * - 시작: `→ GET /api/v1/hello`
 * - 종료: `← GET /api/v1/hello 200 (8ms)`
 * - 두 라인이 같은 traceId로 묶여서 grep 가능
 * - [TraceIdFilter] 다음에 실행되도록 `HIGHEST_PRECEDENCE + 10`
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val startedAt = System.currentTimeMillis()
        log.info("→ {} {}", request.method, request.requestURI)
        try {
            chain.doFilter(request, response)
        } finally {
            val tookMs = System.currentTimeMillis() - startedAt
            log.info("← {} {} {} ({}ms)", request.method, request.requestURI, response.status, tookMs)
        }
    }
}
