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
import java.security.Principal
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

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
    fun `forbidden exceptions log one Forbidden line with caller and path but no query`() {
        val request = MockHttpServletRequest("POST", "/api/payments/refund").apply {
            queryString = "email=a@b.com&token=secret"
            userPrincipal = Principal { "acc_op" }
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        try {
            captureLogger().use { captured ->
                GlobalExceptionHandler().handleApplication(
                    ApplicationException(
                        message = "Missing permission: payment.refund",
                        errorCode = PlatformErrorCode.FORBIDDEN,
                    ),
                )

                val event = captured.events.single()
                assertEquals(Level.WARN, event.level)
                assertEquals(
                    "Forbidden: POST /api/payments/refund principal=acc_op code=COMMON.FORBIDDEN message=Missing permission: payment.refund",
                    event.formattedMessage,
                )
            }
        } finally {
            RequestContextHolder.resetRequestAttributes()
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
