package dev.sumin.skeleton.common

import dev.sumin.skeleton.common.logging.SkeletonLoggers
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 모든 HTTP 요청의 시작/종료를 각각 한 줄씩 로깅.
 *
 * - traceId 는 [TraceIdFilter] 가 먼저 MDC에 주입 → 로그 correlation 패턴 `[traceId]` 에 자동 출력
 * - 시작: `→ GET /api/v1/hello`
 * - 종료: `← GET /api/v1/hello 200 (8ms)`
 * - 오류로 답한 요청([RequestErrorNote])은 종료 줄 끝에 `code=… reason=…` 이 붙는다 (예: `Missing Idempotency-Key`)
 * - 두 라인이 같은 traceId로 묶여서 grep 가능
 * - [TraceIdFilter] 다음에 실행되도록 `HIGHEST_PRECEDENCE + 10`
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestLoggingFilter : OncePerRequestFilter() {
    private val log = SkeletonLoggers.request()

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
            val note = RequestErrorNote.of(request)
            if (note == null) {
                log.info("← {} {} {} ({}ms)", request.method, request.requestURI, response.status, tookMs)
            } else {
                // 오류로 답했으면 이유를 같은 줄에 — 응답 본문을 못 보는 운영 로그에서 원인을 찾는 단서 (코드 · 고정 제목만, 값 없음)
                log.info("← {} {} {} ({}ms) code={} reason={}", request.method, request.requestURI, response.status, tookMs, note.code, note.reason)
            }
        }
    }
}
