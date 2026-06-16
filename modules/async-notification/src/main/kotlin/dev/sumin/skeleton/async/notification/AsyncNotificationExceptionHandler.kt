package dev.sumin.skeleton.async.notification

import dev.sumin.skeleton.async.AsyncMdcKeys
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import java.lang.reflect.Method
import org.slf4j.MDC
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler

class AsyncNotificationExceptionHandler(
    private val publisher: NotificationPublisher,
    private val properties: AsyncNotificationProperties,
) : AsyncUncaughtExceptionHandler {
    private val log = SkeletonLoggers.async()

    override fun handleUncaughtException(
        ex: Throwable,
        method: Method,
        vararg params: Any?,
    ) {
        runCatching {
            publisher.publish(ex.toNotificationEvent(method, params.toList()))
        }.onFailure { publishError ->
            log.warn("Async notification publish failed: {}", publishError.message, publishError)
        }
    }

    private fun Throwable.toNotificationEvent(
        method: Method,
        params: List<Any?>,
    ): NotificationEvent =
        NotificationEvent(
            topic = properties.topic,
            type = properties.type,
            severity = NotificationSeverity.ERROR,
            title = properties.title,
            message = message?.takeIf { it.isNotBlank() } ?: javaClass.name,
            payload = asyncFailurePayload(method, params),
        )

    private fun Throwable.asyncFailurePayload(
        method: Method,
        params: List<Any?>,
    ): Map<String, Any> =
        linkedMapOf<String, Any?>(
            "exceptionClass" to javaClass.name,
            "method" to "${method.declaringClass.simpleName}.${method.name}",
            "argumentCount" to params.size,
            "argumentTypes" to params.map { value -> value?.javaClass?.name ?: "null" },
            "traceId" to MDC.get(TraceIdFilter.MDC_KEY),
            "spanId" to MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            "parentSpanId" to MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY),
            "runId" to MDC.get(AsyncMdcKeys.RUN_ID),
            "accountId" to MDC.get(AsyncMdcKeys.ACCOUNT_ID),
        ).filterValues { value ->
            when (value) {
                null -> false
                is String -> value.isNotBlank()
                else -> true
            }
        }.mapValues { (_, value) -> value!! }
}
