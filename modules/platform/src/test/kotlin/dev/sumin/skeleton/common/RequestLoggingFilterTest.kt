package dev.sumin.skeleton.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import jakarta.servlet.FilterChain
import kotlin.test.Test
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestLoggingFilterTest {
    @Test
    fun `request logs use stable request debug category`() {
        captureLogger(SkeletonLoggers.REQUEST).use { captured ->
            val request = MockHttpServletRequest("GET", "/api/v1/hello")
            val response = MockHttpServletResponse()
            val chain = FilterChain { _, servletResponse ->
                (servletResponse as MockHttpServletResponse).status = 204
            }

            RequestLoggingFilter().doFilter(request, response, chain)

            val messages = captured.events.map { it.formattedMessage }
            assertTrue(messages.any { it == "-> GET /api/v1/hello" || it == "\u2192 GET /api/v1/hello" })
            assertTrue(messages.any { it.startsWith("<- GET /api/v1/hello 204") || it.startsWith("\u2190 GET /api/v1/hello 204") })
        }
    }

    private fun captureLogger(loggerName: String): CapturedLogger {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        logger.level = Level.INFO
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
