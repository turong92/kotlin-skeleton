package dev.sumin.skeleton.redis.cache

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.cache.concurrent.ConcurrentMapCache

class FailOpenRedisCacheErrorHandlerTest {
    @Test
    fun `cache get failure is logged as optional continue and does not throw`() {
        captureLogger(FailOpenRedisCacheErrorHandler::class.java.name).use { captured ->
            val handler = FailOpenRedisCacheErrorHandler()

            handler.handleCacheGetError(
                exception = IllegalStateException("redis down"),
                cache = ConcurrentMapCache("users"),
                key = "account-1",
            )

            assertEquals(Level.WARN, captured.events.single().level)
            assertTrue(captured.events.single().formattedMessage.contains("Optional operation failed"))
            assertTrue(captured.events.single().formattedMessage.contains("continuing"))
            assertTrue(captured.events.single().formattedMessage.contains("redis.cache.get"))
            assertTrue(captured.events.single().formattedMessage.contains("cache=users"))
            assertTrue(captured.events.single().formattedMessage.contains("key=account-1"))
        }
    }

    private fun captureLogger(name: String): CapturedLogger {
        val logger = LoggerFactory.getLogger(name) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.TRACE
        logger.isAdditive = false
        return CapturedLogger(logger, appender)
    }

    private class CapturedLogger(
        private val logger: Logger,
        private val appender: ListAppender<ILoggingEvent>,
    ) : AutoCloseable {
        val events: List<ILoggingEvent>
            get() = appender.list

        override fun close() {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
