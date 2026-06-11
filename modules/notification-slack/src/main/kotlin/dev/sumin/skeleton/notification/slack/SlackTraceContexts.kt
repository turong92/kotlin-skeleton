package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.TraceIdFilter
import org.slf4j.MDC

internal object SlackTraceContexts {
    fun current(): SlackTraceContext =
        SlackTraceContext(
            traceId = MDC.get(TraceIdFilter.MDC_KEY),
            spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            parentSpanId = MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY),
        )
}
