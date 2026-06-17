package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.common.TraceIdFilter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.slf4j.MDC
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

class NotificationWebSocketTraceInterceptorTest {
    @AfterTest
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `pre send scopes MDC for inbound interceptor chain and restores it after send`() {
        val interceptor = NotificationWebSocketTraceInterceptor()
        val message = stompMessage(
            command = StompCommand.CONNECT,
            "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
        )
        MDC.put("caller", "before")

        interceptor.preSend(message, NoopMessageChannel)

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get(TraceIdFilter.MDC_KEY))
        assertEquals("00f067aa0ba902b7", MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY))

        interceptor.afterSendCompletion(message, NoopMessageChannel, true, null)

        assertEquals("before", MDC.get("caller"))
        assertNull(MDC.get(TraceIdFilter.MDC_KEY))
    }

    @Test
    fun `inbound stomp traceparent scopes MDC while handling frame`() {
        val interceptor = NotificationWebSocketTraceInterceptor()
        val message = stompMessage(
            command = StompCommand.SUBSCRIBE,
            "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "destination" to "/topic/notifications/demo",
        )
        MDC.put("caller", "before")

        val tracedMessage = interceptor.beforeHandle(message, NoopMessageChannel, NoopMessageHandler)!!

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get(TraceIdFilter.MDC_KEY))
        assertEquals("00f067aa0ba902b7", MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY))
        assertNotNull(MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY))
        assertFalse(MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY) == "00f067aa0ba902b7")

        interceptor.afterMessageHandled(tracedMessage, NoopMessageChannel, NoopMessageHandler, null)

        assertEquals("before", MDC.get("caller"))
        assertNull(MDC.get(TraceIdFilter.MDC_KEY))
    }

    @Test
    fun `x trace id native header is used when traceparent is absent`() {
        val interceptor = NotificationWebSocketTraceInterceptor()
        val message = stompMessage(
            command = StompCommand.CONNECT,
            "X-Trace-Id" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            sessionId = "session-1",
        )

        interceptor.beforeHandle(message, NoopMessageChannel, NoopMessageHandler)!!

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", MDC.get(TraceIdFilter.MDC_KEY))
        assertNull(MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY))
    }

    @Test
    fun `disconnect without native trace reuses session trace`() {
        val interceptor = NotificationWebSocketTraceInterceptor()
        val connect = stompMessage(
            command = StompCommand.CONNECT,
            "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            sessionId = "session-1",
        )
        val disconnect = stompMessage(
            command = StompCommand.DISCONNECT,
            sessionId = "session-1",
        )

        interceptor.beforeHandle(connect, NoopMessageChannel, NoopMessageHandler)
        interceptor.afterMessageHandled(connect, NoopMessageChannel, NoopMessageHandler, null)

        interceptor.preSend(disconnect, NoopMessageChannel)

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", MDC.get(TraceIdFilter.MDC_KEY))
        assertEquals("00f067aa0ba902b7", MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY))
    }

    private fun stompMessage(
        command: StompCommand,
        vararg nativeHeaders: Pair<String, String>,
        sessionId: String? = null,
    ): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        accessor.sessionId = sessionId
        nativeHeaders.forEach { (name, value) -> accessor.addNativeHeader(name, value) }
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    private object NoopMessageChannel : MessageChannel {
        override fun send(message: Message<*>): Boolean = true

        override fun send(
            message: Message<*>,
            timeout: Long,
        ): Boolean = true
    }

    private object NoopMessageHandler : MessageHandler {
        override fun handleMessage(message: Message<*>) = Unit
    }
}
