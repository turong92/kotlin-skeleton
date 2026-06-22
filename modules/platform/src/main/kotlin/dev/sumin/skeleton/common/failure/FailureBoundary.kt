package dev.sumin.skeleton.common.failure

import org.slf4j.Logger

class FailureBoundary(
    private val log: Logger,
) {
    fun <T> run(
        operation: String,
        policy: FailurePolicy,
        context: Map<String, Any?> = emptyMap(),
        fallback: () -> T,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: Throwable) {
            when (policy) {
                FailurePolicy.THROW -> throw failure
                FailurePolicy.LOG_AND_CONTINUE -> {
                    log.warn(
                        "Optional operation failed; continuing operation={}{}: {}",
                        operation,
                        context.toLogSuffix(),
                        failure.message,
                        failure,
                    )
                    fallback()
                }
                FailurePolicy.LOG_AND_SKIP -> {
                    log.warn(
                        "Optional operation failed; skipping operation={}{}: {}",
                        operation,
                        context.toLogSuffix(),
                        failure.message,
                        failure,
                    )
                    fallback()
                }
            }
        }

    fun <T> run(
        operation: String,
        policy: FailurePolicy,
        context: Map<String, Any?> = emptyMap(),
        block: () -> T,
    ): T =
        run(
            operation = operation,
            policy = policy,
            context = context,
            fallback = { throw IllegalStateException("No fallback configured") },
            block = block,
        )

    fun <T : Any> runOrSkip(
        operation: String,
        policy: FailurePolicy,
        context: Map<String, Any?> = emptyMap(),
        block: () -> T,
    ): T? =
        run(operation = operation, policy = policy, context = context, fallback = { null }, block = block)

    private fun Map<String, Any?>.toLogSuffix(): String {
        val entries = entries
            .mapNotNull { (key, value) ->
                val normalized = value?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "$key=$normalized"
            }
        return entries.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " context={", postfix = "}")
            .orEmpty()
    }
}
