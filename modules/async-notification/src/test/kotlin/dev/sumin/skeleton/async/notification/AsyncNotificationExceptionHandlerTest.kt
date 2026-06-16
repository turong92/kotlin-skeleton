package dev.sumin.skeleton.async.notification

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.async.AsyncMdcKeys
import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import java.lang.reflect.Method
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class AsyncNotificationExceptionHandlerTest {
    @AfterTest
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `uncaught async exception publishes notification event with trace run and method metadata`() {
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")
        MDC.put(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY, "1111111111111111")
        MDC.put(AsyncMdcKeys.RUN_ID, "run-123")
        MDC.put(AsyncMdcKeys.ACCOUNT_ID, "acc-user")
        val events = mutableListOf<NotificationEvent>()
        val handler = AsyncNotificationExceptionHandler(
            publisher = recordingPublisher(events),
            properties = AsyncNotificationProperties(),
        )

        handler.handleUncaughtException(
            IllegalStateException("boom"),
            SampleAsyncTarget::class.java.getDeclaredMethod("sync", String::class.java, Int::class.javaPrimitiveType),
            "raw-secret-value",
            7,
        )

        val event = events.single()
        assertEquals("async.exception", event.topic)
        assertEquals("async-exception", event.type)
        assertEquals(NotificationSeverity.ERROR, event.severity)
        assertEquals("Async task failed", event.title)
        assertEquals("boom", event.message)
        assertEquals("java.lang.IllegalStateException", event.payload["exceptionClass"])
        assertEquals("SampleAsyncTarget.sync", event.payload["method"])
        assertEquals(2, event.payload["argumentCount"])
        assertEquals(listOf("java.lang.String", "java.lang.Integer"), event.payload["argumentTypes"])
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", event.payload["traceId"])
        assertEquals("00f067aa0ba902b7", event.payload["spanId"])
        assertEquals("1111111111111111", event.payload["parentSpanId"])
        assertEquals("run-123", event.payload["runId"])
        assertEquals("acc-user", event.payload["accountId"])
        assertFalse(event.payload.values.any { it == "raw-secret-value" })
    }

    @Test
    fun `uncaught async exception omits blank context values`() {
        val events = mutableListOf<NotificationEvent>()
        val handler = AsyncNotificationExceptionHandler(
            publisher = recordingPublisher(events),
            properties = AsyncNotificationProperties(topic = "jobs", type = "failed", title = "Job failed"),
        )

        handler.handleUncaughtException(
            RuntimeException(),
            SampleAsyncTarget::class.java.getDeclaredMethod("noArgs"),
        )

        val event = events.single()
        assertEquals("jobs", event.topic)
        assertEquals("failed", event.type)
        assertEquals("Job failed", event.title)
        assertEquals("java.lang.RuntimeException", event.message)
        assertFalse(event.payload.containsKey("traceId"))
        assertFalse(event.payload.containsKey("spanId"))
        assertFalse(event.payload.containsKey("parentSpanId"))
        assertFalse(event.payload.containsKey("runId"))
        assertFalse(event.payload.containsKey("accountId"))
    }

    @Test
    fun `notification publishing failure is logged and swallowed`() {
        val handler = AsyncNotificationExceptionHandler(
            publisher = NotificationPublisher { throw IllegalStateException("publisher down") },
            properties = AsyncNotificationProperties(),
        )

        captureLogger(SkeletonLoggers.ASYNC).use { captured ->
            handler.handleUncaughtException(
                IllegalArgumentException("bad"),
                SampleAsyncTarget::class.java.getDeclaredMethod("noArgs"),
            )

            assertTrue(captured.events.any { it.formattedMessage.contains("Async notification publish failed") })
        }
    }

    private fun recordingPublisher(events: MutableList<NotificationEvent>): NotificationPublisher =
        NotificationPublisher { event ->
            events += event
            NotificationPublishResult(eventId = event.id, deliveredSubscribers = 1)
        }

    private fun captureLogger(loggerName: String): CapturedLogger {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val previousLevel = logger.level
        val previousAdditive = logger.isAdditive
        logger.level = Level.WARN
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

    private class SampleAsyncTarget {
        @Suppress("unused")
        fun sync(
            value: String,
            count: Int,
        ) = Unit

        @Suppress("unused")
        fun noArgs() = Unit
    }
}
