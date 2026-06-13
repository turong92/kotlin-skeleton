package dev.sumin.skeleton.common.http

import org.springframework.http.HttpHeaders

data class ExternalHttpTrace(
    val traceId: String?,
    val source: String?,
) {
    companion object {
        val NONE = ExternalHttpTrace(traceId = null, source = null)
    }
}

fun interface ExternalHttpTraceExtractor {
    fun extract(headers: HttpHeaders, candidateHeaders: List<String>): ExternalHttpTrace
}

class DefaultExternalHttpTraceExtractor : ExternalHttpTraceExtractor {
    override fun extract(headers: HttpHeaders, candidateHeaders: List<String>): ExternalHttpTrace {
        candidateHeaders
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { headerName ->
                val value = headers.getFirst(headerName)?.takeIf { it.isNotBlank() }
                if (value != null) {
                    return ExternalHttpTrace(traceId = value, source = headerName)
                }
            }
        return ExternalHttpTrace.NONE
    }

    companion object {
        val DEFAULT_HEADER_NAMES = listOf(
            "X-Provider-Trace-Id",
            "X-Request-Id",
            "X-Correlation-Id",
            "X-Amzn-Trace-Id",
            "Stripe-Request-Id",
            "Request-Id",
        )
    }
}
