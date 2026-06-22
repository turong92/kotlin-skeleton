package dev.sumin.skeleton.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerLoggingTest {
    @Test
    fun `client application exceptions are logged as warn without stack trace`() {
        captureLogger().use { captured ->
            GlobalExceptionHandler().handleApplication(
                ApplicationException(
                    message = "cannot update shipped order",
                    errorCode = SimpleErrorCode(
                        code = "ORDER.INVALID_STATE",
                        status = HttpStatus.CONFLICT,
                        title = "Invalid order state",
                    ),
                ),
            )

            val event = captured.events.single()
            assertEquals(Level.WARN, event.level)
            assertNull(event.throwableProxy)
        }
    }

    @Test
    fun `server application exceptions are logged as error with stack trace`() {
        captureLogger().use { captured ->
            GlobalExceptionHandler().handleApplication(
                ApplicationException(
                    message = "payment provider failed",
                    errorCode = SimpleErrorCode(
                        code = "PAYMENT.PROVIDER_ERROR",
                        status = HttpStatus.BAD_GATEWAY,
                        title = "Payment provider error",
                    ),
                    cause = IllegalStateException("provider timeout"),
                ),
            )

            val event = captured.events.single()
            assertEquals(Level.ERROR, event.level)
            assertNotNull(event.throwableProxy)
        }
    }

    private fun captureLogger(): CapturedLogger {
        val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.level = Level.TRACE
        logger.isAdditive = false
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
