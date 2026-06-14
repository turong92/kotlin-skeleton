package dev.sumin.skeleton.common.http

import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.logging.SensitiveValueRedactor
import java.time.Duration
import java.util.UUID
import org.slf4j.Logger
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import reactor.core.publisher.Mono

internal class ExternalHttpFilters(
    private val properties: OutboundHttpProperties,
    private val log: Logger,
    private val redactor: SensitiveValueRedactor,
) {
    fun tracePropagationFilter(): ExchangeFilterFunction =
        ExchangeFilterFunction.ofRequestProcessor { request ->
            val traceId = MDC.get(TraceIdFilter.MDC_KEY) ?: randomTraceId()
            val spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY) ?: randomSpanId()
            Mono.just(
                ClientRequest.from(request)
                    .headers { headers ->
                        headers.set(TraceIdFilter.HEADER_TRACEPARENT, TraceIdFilter.formatTraceparent(traceId, spanId))
                        headers.set(TraceIdFilter.HEADER_TRACE_ID, traceId)
                    }
                    .build(),
            )
        }

    fun loggingFilter(): ExchangeFilterFunction =
        ExchangeFilterFunction.ofResponseProcessor { response ->
            if (properties.logging.enabled) {
                log.debug("External HTTP response status={}", response.statusCode().value())
            }
            Mono.just(response)
        }

    fun logCompletion(
        clientName: String,
        method: HttpMethod,
        path: String,
        startedAt: Long,
        requestSpec: ExternalHttpRequestSpec,
    ) {
        if (!properties.logging.enabled) return
        val tookMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        log.info(
            "External HTTP {} {} {} ({}ms)",
            method.name(),
            requestSpec.loggingTag ?: clientName,
            sanitizedPath(path),
            tookMs,
        )
    }

    private fun sanitizedPath(path: String): String =
        if (properties.logging.includeQuery) {
            redactQuery(path)
        } else {
            path.substringBefore("?")
        }

    private fun redactQuery(path: String): String {
        val querySeparator = path.indexOf("?")
        if (querySeparator < 0) return path
        val basePath = path.substring(0, querySeparator)
        val query = path.substring(querySeparator + 1)
        return "$basePath?${redactor.redactQueryString(query)}"
    }

    private fun randomTraceId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun randomSpanId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
