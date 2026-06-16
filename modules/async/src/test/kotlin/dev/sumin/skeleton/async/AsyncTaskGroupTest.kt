package dev.sumin.skeleton.async

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class AsyncTaskGroupTest {
    @AfterTest
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `waitAll collects named successes failures and duration`() {
        val success = CompletableFuture.completedFuture("ok")
        val failure = CompletableFuture<String>().also {
            it.completeExceptionally(IllegalStateException("boom"))
        }

        val result = AsyncTaskGroup.waitAll(
            tasks = mapOf(
                "success-task" to success,
                "failure-task" to failure,
            ),
        )

        assertEquals(listOf("success-task"), result.succeeded)
        assertEquals(1, result.failed.size)
        assertEquals("failure-task", result.failed.single().name)
        assertIs<IllegalStateException>(result.failed.single().error)
        assertTrue(result.durationMillis >= 0)
        assertTrue(result.hasFailures)
    }

    @Test
    fun `waitAll records timeout as named failure without blocking forever`() {
        val never = CompletableFuture<String>()

        val result = AsyncTaskGroup.waitAll(
            tasks = mapOf("slow-task" to never),
            timeout = Duration.ofMillis(10),
        )

        assertEquals(emptyList(), result.succeeded)
        assertEquals("slow-task", result.failed.single().name)
        assertIs<TimeoutException>(result.failed.single().error)
    }

    @Test
    fun `throwIfFailures throws one exception with suppressed task failures`() {
        val result = AsyncTaskGroup.waitAll(
            tasks = mapOf(
                "bad-a" to failedFuture(IllegalArgumentException("bad-a")),
                "bad-b" to failedFuture(IllegalStateException("bad-b")),
            ),
        )

        val exception = assertFailsWith<AsyncTaskGroupException> {
            result.throwIfFailures()
        }

        assertEquals("Async task group failed for tasks: bad-a, bad-b", exception.message)
        assertEquals(2, exception.suppressed.size)
    }

    @Test
    fun `waitAllAndLog writes non null async context into summary and failure logs`() {
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")
        MDC.put(AsyncMdcKeys.RUN_ID, "run-log")
        MDC.put(AsyncMdcKeys.ACCOUNT_ID, "acc-log")

        captureLogger(SkeletonLoggers.ASYNC).use { captured ->
            AsyncTaskGroup.waitAllAndLog(
                tasks = mapOf(
                    "good" to CompletableFuture.completedFuture("ok"),
                    "bad" to failedFuture(IllegalArgumentException("broken")),
                ),
                title = "content sync",
            )

            val messages = captured.events.map { it.formattedMessage }
            assertTrue(
                messages.any {
                    it.contains("content sync completed") &&
                        it.contains("traceId=4bf92f3577b34da6a3ce929d0e0e4736") &&
                        it.contains("spanId=00f067aa0ba902b7") &&
                        it.contains("runId=run-log") &&
                        it.contains("accountId=acc-log")
                },
            )
            assertTrue(messages.any { it.contains("content sync task failed: bad") })
            assertFalse(messages.any { it.contains("parentSpanId=null") })
        }
    }

    private fun failedFuture(error: Throwable): CompletableFuture<String> =
        CompletableFuture<String>().also { it.completeExceptionally(error) }

    private fun captureLogger(loggerName: String): CapturedLogger {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        logger.level = Level.INFO
        logger.isAdditive = false
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return CapturedLogger(logger, appender, previousLevel, previousAdditive)
    }

    private class CapturedLogger(
        private val logger: Logger,
        private val appender: ListAppender<ILoggingEvent>,
        private val previousLevel: Level?,
        private val previousAdditive: Boolean,
    ) : AutoCloseable {
        val events: List<ILoggingEvent>
            get() = appender.list

        override fun close() {
            logger.detachAppender(appender)
            logger.level = previousLevel
            logger.isAdditive = previousAdditive
            appender.stop()
        }
    }
}
