package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.TraceIdFilter
import java.lang.reflect.Method
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.Signature
import org.aspectj.lang.reflect.MethodSignature
import org.aspectj.lang.reflect.SourceLocation
import org.slf4j.MDC

class SlackExceptionAspectTest {
    @AfterTest
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `sends exception alert with trace and contributor fields`() {
        val sender = RecordingSlackAlertSender()
        val aspect = SlackExceptionAspect(
            sender = sender,
            contributors = listOf(
                SlackAlertContextContributor { mapOf("accountId" to "account-1") },
            ),
            properties = SlackNotificationProperties(defaultTopic = "operations"),
        )
        val method = Fixture::class.java.getDeclaredMethod("notifyPayment", String::class.java)
        val annotation = method.getAnnotation(SlackExceptionNotify::class.java)
        MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")

        aspect.notifyAfterThrowing(
            joinPoint = RecordingJoinPoint(method, arrayOf("order-1")),
            slackExceptionNotify = annotation,
            throwable = IllegalStateException("approve failed"),
        )

        val alert = sender.alerts.single()
        assertEquals("Payment failure", alert.title)
        assertEquals("approve failed", alert.message)
        assertEquals(SlackAlertSeverity.ERROR, alert.severity)
        assertEquals("payment", alert.route)
        assertEquals("account-1", alert.fields["accountId"])
        assertEquals("IllegalStateException", alert.fields["exception"])
        assertEquals("Fixture.notifyPayment", alert.fields["method"])
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", alert.trace.traceId)
        assertEquals("00f067aa0ba902b7", alert.trace.spanId)
    }

    @Test
    fun `does not send excluded exception`() {
        val sender = RecordingSlackAlertSender()
        val aspect = SlackExceptionAspect(
            sender = sender,
            contributors = emptyList(),
            properties = SlackNotificationProperties(),
        )
        val method = Fixture::class.java.getDeclaredMethod("excluded")

        aspect.notifyAfterThrowing(
            joinPoint = RecordingJoinPoint(method),
            slackExceptionNotify = method.getAnnotation(SlackExceptionNotify::class.java),
            throwable = IllegalArgumentException("skip"),
        )

        assertTrue(sender.alerts.isEmpty())
    }

    private class RecordingSlackAlertSender : SlackAlertSender {
        val alerts = mutableListOf<SlackAlert>()

        override fun send(alert: SlackAlert) {
            alerts += alert
        }
    }

    private class RecordingJoinPoint(
        private val method: Method,
        private val arguments: Array<Any?> = emptyArray(),
    ) : JoinPoint {
        override fun toShortString(): String = method.name
        override fun toLongString(): String = method.toString()
        override fun getThis(): Any? = null
        override fun getTarget(): Any? = null
        override fun getArgs(): Array<Any?> = arguments
        override fun getSignature(): Signature = RecordingMethodSignature(method)
        override fun getSourceLocation(): SourceLocation? = null
        override fun getKind(): String = JoinPoint.METHOD_EXECUTION
        override fun getStaticPart(): JoinPoint.StaticPart? = null
    }

    private class RecordingMethodSignature(
        private val method: Method,
    ) : MethodSignature {
        override fun toShortString(): String = method.name
        override fun toLongString(): String = method.toString()
        override fun getName(): String = method.name
        override fun getModifiers(): Int = method.modifiers
        override fun getDeclaringType(): Class<*> = method.declaringClass
        override fun getDeclaringTypeName(): String = method.declaringClass.name
        override fun getParameterNames(): Array<String> = emptyArray()
        override fun getParameterTypes(): Array<Class<*>> = method.parameterTypes
        override fun getExceptionTypes(): Array<Class<*>> = method.exceptionTypes
        override fun getReturnType(): Class<*> = method.returnType
        override fun getMethod(): Method = method
    }

    private class Fixture {
        @SlackExceptionNotify(route = "payment", title = "Payment failure")
        fun notifyPayment(orderId: String) = orderId

        @SlackExceptionNotify(exclude = [IllegalArgumentException::class])
        fun excluded() = Unit
    }
}
