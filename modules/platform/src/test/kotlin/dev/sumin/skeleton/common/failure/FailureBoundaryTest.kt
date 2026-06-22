package dev.sumin.skeleton.common.failure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class FailureBoundaryTest {
    @Test
    fun `throw policy rethrows original exception without duplicate logging`() {
        val logger = captureLogger()
        val boundary = FailureBoundary(logger.logger)
        val failure = IllegalStateException("payment approval failed")

        val thrown = kotlin.runCatching {
            boundary.run(
                operation = "payment.approve",
                policy = FailurePolicy.THROW,
            ) {
                throw failure
            }
        }.exceptionOrNull()

        assertSame(failure, thrown)
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `log and continue policy returns fallback and writes warning log`() {
        val logger = captureLogger()
        val boundary = FailureBoundary(logger.logger)

        val result = boundary.run(
            operation = "notification.publish",
            policy = FailurePolicy.LOG_AND_CONTINUE,
            context = mapOf("topic" to "orders", "subscriberCount" to 3),
            fallback = { "fallback-value" },
        ) {
            error("notification backend down")
        }

        assertEquals("fallback-value", result)
        assertEquals(Level.WARN, logger.events.single().level)
        assertTrue(logger.events.single().formattedMessage.contains("notification.publish"))
        assertTrue(logger.events.single().formattedMessage.contains("topic=orders"))
        assertTrue(logger.events.single().formattedMessage.contains("subscriberCount=3"))
        assertTrue(logger.events.single().formattedMessage.contains("continuing"))
    }

    @Test
    fun `log and skip policy returns null and writes warning log`() {
        val logger = captureLogger()
        val boundary = FailureBoundary(logger.logger)

        val result = boundary.runOrSkip(
            operation = "cache.put",
            policy = FailurePolicy.LOG_AND_SKIP,
        ) {
            error("redis is unavailable")
        }

        assertNull(result)
        assertEquals(Level.WARN, logger.events.single().level)
        assertTrue(logger.events.single().formattedMessage.contains("cache.put"))
        assertTrue(logger.events.single().formattedMessage.contains("skipping"))
    }

    private fun captureLogger(): CapturedLogger {
        val logger = LoggerFactory.getLogger("test.failure-boundary.${System.nanoTime()}") as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.TRACE
        logger.isAdditive = false
        return CapturedLogger(logger, appender)
    }

    private class CapturedLogger(
        val logger: Logger,
        private val appender: ListAppender<ILoggingEvent>,
    ) {
        val events: List<ILoggingEvent>
            get() = appender.list
    }
}
