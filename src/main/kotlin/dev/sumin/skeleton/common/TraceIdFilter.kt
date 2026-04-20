package dev.sumin.skeleton.common

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 모든 HTTP 요청에 `traceId`를 부여하고 MDC에 넣어 로그 라인마다 출력되게 한다.
 *
 * - 클라이언트가 `X-Request-Id` 헤더로 보내면 **그 값을 승계** → 프론트/백 로그 상관관계 연결
 * - 없으면 UUID 생성 (하이픈 제거 후 앞 16자)
 * - 응답 헤더 `X-Trace-Id`로 traceId 반환 → 프론트 콘솔/토스트에서 바로 확인 가능
 * - 에러 응답 body에도 [GlobalExceptionHandler]가 [MDC_KEY]를 읽어 포함
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val traceId = request.getHeader(HEADER_REQUEST_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        try {
            MDC.put(MDC_KEY, traceId)
            response.setHeader(HEADER_TRACE_ID, traceId)
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val MDC_KEY = "traceId"
        const val HEADER_REQUEST_ID = "X-Request-Id"
        const val HEADER_TRACE_ID = "X-Trace-Id"
    }
}
