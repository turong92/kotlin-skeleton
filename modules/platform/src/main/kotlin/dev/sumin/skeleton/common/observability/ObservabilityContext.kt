package dev.sumin.skeleton.common.observability

import dev.sumin.skeleton.common.TraceIdFilter
import org.slf4j.MDC

data class ObservabilityContext(
    private val values: Map<String, String?> = emptyMap(),
) {
    fun value(name: String): String? =
        values[name]?.trim()?.takeIf { it.isNotBlank() }

    fun asMap(): Map<String, String> =
        values.mapNotNull { (name, value) -> value?.trim()?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap()

    fun withValues(additionalValues: Map<String, Any?>): ObservabilityContext =
        ObservabilityContext(values + additionalValues.toStringValues())

    companion object {
        val knownFields: Set<String> = setOf(
            "traceId",
            "spanId",
            "parentSpanId",
            "runId",
            "accountId",
            "errorCode",
            "provider",
            "providerTraceId",
            "providerRequestId",
            "providerOperationId",
            "topic",
            "type",
            "route",
            "method",
        )

        fun of(vararg pairs: Pair<String, Any?>): ObservabilityContext =
            ObservabilityContext(pairs.toMap().toStringValues())

        fun fromMdc(additionalValues: Map<String, Any?> = emptyMap()): ObservabilityContext =
            ObservabilityContext(
                mapOf(
                    "traceId" to MDC.get(TraceIdFilter.MDC_KEY),
                    "spanId" to MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
                    "parentSpanId" to MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY),
                ) + additionalValues.toStringValues(),
            )

        private fun Map<String, Any?>.toStringValues(): Map<String, String?> =
            mapValues { (_, value) -> value?.toString() }
    }
}
