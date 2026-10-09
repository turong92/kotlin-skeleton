package dev.sumin.skeleton.common

import dev.sumin.skeleton.common.logging.LogMasker
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
 * - 경로는 [LogMasker.maskRequestPath] 를 거쳐 찍는다 — 모듈이 [dev.sumin.skeleton.common.logging.LogMaskingContributor] 로 더한 규칙(주소에 실린 비밀 조각)**만**, 512자로 자른 뒤에 적용된다(앱의 logback 설정과 무관하게 이 줄에 닿는다).
 *   이 필터는 인증 · 요청 수 한도보다 앞이라 아무 호출자의 경로를 받는다 — 플랫폼의 기본 가림 규칙은 태우지 않는다. 기여자가 없으면 경로는 그대로다
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
        val path = LogMasker.current.maskRequestPath(request.requestURI)
        log.info("→ {} {}", request.method, path)
        try {
            chain.doFilter(request, response)
        } finally {
            val tookMs = System.currentTimeMillis() - startedAt
            val note = RequestErrorNote.of(request)
            if (note == null) {
                log.info("← {} {} {} ({}ms)", request.method, path, response.status, tookMs)
            } else {
                // 오류로 답했으면 이유를 같은 줄에 — 응답 본문을 못 보는 운영 로그에서 원인을 찾는 단서 (코드 · 고정 제목만, 값 없음)
                log.info("← {} {} {} ({}ms) code={} reason={}", request.method, path, response.status, tookMs, note.code, note.reason)
            }
        }
    }
}
