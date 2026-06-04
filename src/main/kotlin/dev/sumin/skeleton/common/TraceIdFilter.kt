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
 * W3C Trace Context 를 요청마다 MDC에 주입.
 *
 * - 클라이언트가 `traceparent` 헤더로 보내면 traceId 를 승계
 * - 현재 BE 요청은 새 spanId 를 생성해서 처리
 * - 없거나 잘못된 traceparent 면 새 root traceId/spanId 생성
 * - 응답 헤더 `traceparent`, `X-Trace-Id`, `X-Span-Id`로 현재 서버 span 반환
 * - 다운스트림 처리(컨트롤러, [GlobalExceptionHandler], [RequestLoggingFilter])가 MDC를 참조
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val incoming = parseTraceparent(request.getHeader(HEADER_TRACEPARENT))
        val traceId = incoming?.traceId
            ?: legacyTraceId(request.getHeader(HEADER_REQUEST_ID))
            ?: randomTraceId()
        val spanId = randomSpanId()
        val flags = incoming?.flags ?: DEFAULT_FLAGS
        try {
            MDC.put(MDC_KEY, traceId)
            MDC.put(MDC_SPAN_ID_KEY, spanId)
            if (incoming?.parentSpanId != null) {
                MDC.put(MDC_PARENT_SPAN_ID_KEY, incoming.parentSpanId)
            }
            MDC.put(MDC_TRACE_CONTEXT_KEY, formatLogContext(traceId, spanId, incoming?.parentSpanId))
            val outgoingTraceparent = formatTraceparent(traceId, spanId, flags)
            response.setHeader(HEADER_TRACE_ID, traceId)
            response.setHeader(HEADER_SPAN_ID, spanId)
            response.setHeader(HEADER_TRACEPARENT, outgoingTraceparent)
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
            MDC.remove(MDC_SPAN_ID_KEY)
            MDC.remove(MDC_PARENT_SPAN_ID_KEY)
            MDC.remove(MDC_TRACE_CONTEXT_KEY)
        }
    }

    companion object {
        const val MDC_KEY = "traceId"
        const val MDC_SPAN_ID_KEY = "spanId"
        const val MDC_PARENT_SPAN_ID_KEY = "parentSpanId"
        const val MDC_TRACE_CONTEXT_KEY = "traceContext"
        const val HEADER_TRACEPARENT = "traceparent"
        const val HEADER_REQUEST_ID = "X-Request-Id"
        const val HEADER_TRACE_ID = "X-Trace-Id"
        const val HEADER_SPAN_ID = "X-Span-Id"

        private const val VERSION = "00"
        private const val DEFAULT_FLAGS = "01"
        private val TRACEPARENT_REGEX = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
        private val TRACE_ID_REGEX = Regex("^[0-9a-f]{32}$")

        fun formatTraceparent(traceId: String, spanId: String, flags: String = DEFAULT_FLAGS): String =
            "$VERSION-$traceId-$spanId-$flags"

        fun formatLogContext(traceId: String, spanId: String, parentSpanId: String?): String =
            buildString {
                append("[traceId=")
                append(traceId)
                append(" spanId=")
                append(spanId)
                if (!parentSpanId.isNullOrBlank()) {
                    append(" parentSpanId=")
                    append(parentSpanId)
                }
                append("]")
            }

        private fun parseTraceparent(value: String?): IncomingTraceContext? {
            val match = value?.let { TRACEPARENT_REGEX.matchEntire(it.trim()) } ?: return null
            val traceId = match.groupValues[1]
            val parentSpanId = match.groupValues[2]
            val flags = match.groupValues[3]
            if (traceId.all { it == '0' } || parentSpanId.all { it == '0' }) return null
            return IncomingTraceContext(traceId, parentSpanId, flags)
        }

        private fun legacyTraceId(value: String?): String? =
            value?.lowercase()?.takeIf { TRACE_ID_REGEX.matches(it) && it.any { char -> char != '0' } }

        private fun randomTraceId(): String = UUID.randomUUID().toString().replace("-", "")

        private fun randomSpanId(): String =
            UUID.randomUUID().toString().replace("-", "").take(16)

        private data class IncomingTraceContext(
            val traceId: String,
            val parentSpanId: String,
            val flags: String,
        )
    }
}
