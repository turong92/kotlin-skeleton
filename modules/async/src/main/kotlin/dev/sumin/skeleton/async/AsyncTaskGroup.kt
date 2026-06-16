package dev.sumin.skeleton.async

import dev.sumin.skeleton.common.logging.SkeletonLoggers
import dev.sumin.skeleton.common.TraceIdFilter
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.slf4j.Logger
import org.slf4j.MDC

data class AsyncTaskFailure(
    val name: String,
    val error: Throwable,
)

data class AsyncTaskGroupResult(
    val succeeded: List<String>,
    val failed: List<AsyncTaskFailure>,
    val durationMillis: Long,
) {
    val hasFailures: Boolean get() = failed.isNotEmpty()
    val total: Int get() = succeeded.size + failed.size

    fun logSummary(
        logger: Logger = SkeletonLoggers.async(),
        title: String = "Async task group",
    ) {
        val context = asyncLogContextSuffix()
        logger.info(
            "{} completed in {}ms | total={}, success={}, failed={}{}",
            title,
            durationMillis,
            total,
            succeeded.size,
            failed.size,
            context,
        )
        failed.forEach { failure ->
            logger.error("{} task failed: {}{}", title, failure.name, context, failure.error)
        }
    }

    fun throwIfFailures() {
        if (failed.isEmpty()) {
            return
        }

        throw AsyncTaskGroupException(
            message = "Async task group failed for tasks: ${failed.joinToString { it.name }}",
            failed = failed,
        )
    }
}

class AsyncTaskGroupException(
    message: String,
    failed: List<AsyncTaskFailure>,
) : RuntimeException(message) {
    init {
        failed.forEach { failure -> addSuppressed(failure.error) }
    }
}

private fun asyncLogContextSuffix(): String {
    val entries = listOfNotNull(
        mdcEntry("traceId", TraceIdFilter.MDC_KEY),
        mdcEntry("spanId", TraceIdFilter.MDC_SPAN_ID_KEY),
        mdcEntry("parentSpanId", TraceIdFilter.MDC_PARENT_SPAN_ID_KEY),
        mdcEntry("runId", AsyncMdcKeys.RUN_ID),
        mdcEntry("accountId", AsyncMdcKeys.ACCOUNT_ID),
    )
    return if (entries.isEmpty()) "" else entries.joinToString(prefix = " | ", separator = " ")
}

private fun mdcEntry(
    label: String,
    key: String,
): String? =
    MDC.get(key)
        ?.takeIf { it.isNotBlank() }
        ?.let { "$label=$it" }

object AsyncTaskGroup {
    fun waitAll(
        tasks: Map<String, CompletableFuture<*>>,
        timeout: Duration? = null,
    ): AsyncTaskGroupResult {
        val startedAt = System.nanoTime()
        val handled = tasks.mapValues { (_, task) ->
            task
                .withTimeout(timeout)
                .handle { _, error -> error?.let(::unwrap) }
        }

        runCatching {
            CompletableFuture.allOf(*handled.values.toTypedArray()).join()
        }

        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<AsyncTaskFailure>()

        handled.forEach { (name, task) ->
            val error = runCatching { task.join() }.getOrElse(::unwrap)
            if (error == null) {
                succeeded += name
            } else {
                failed += AsyncTaskFailure(name, error)
            }
        }

        return AsyncTaskGroupResult(
            succeeded = succeeded,
            failed = failed,
            durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
        )
    }

    fun waitAllAndLog(
        tasks: Map<String, CompletableFuture<*>>,
        logger: Logger = SkeletonLoggers.async(),
        title: String = "Async task group",
        timeout: Duration? = null,
        throwOnFailures: Boolean = false,
    ): AsyncTaskGroupResult {
        val result = waitAll(tasks = tasks, timeout = timeout)
        result.logSummary(logger = logger, title = title)
        if (throwOnFailures) {
            result.throwIfFailures()
        }
        return result
    }

    private fun CompletableFuture<*>.withTimeout(timeout: Duration?): CompletableFuture<*> {
        if (timeout == null) {
            return this
        }
        val timeoutMillis = timeout.toMillis().coerceAtLeast(1)
        return orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun unwrap(error: Throwable): Throwable =
        when (error) {
            is CompletionException -> error.cause?.let(::unwrap) ?: error
            is ExecutionException -> error.cause?.let(::unwrap) ?: error
            else -> error
        }
}
