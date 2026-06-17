package dev.sumin.skeleton.notification.websocket

import dev.sumin.skeleton.common.TraceIdFilter
import dev.sumin.skeleton.common.logging.SkeletonLoggers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.MDC
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHandler
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ExecutorChannelInterceptor

class NotificationWebSocketTraceInterceptor : ExecutorChannelInterceptor {
    private val log = SkeletonLoggers.websocket()
    private val previousSendMdc = ThreadLocal<Map<String, String>?>()
    private val previousHandleMdc = ThreadLocal<Map<String, String>?>()
    private val sessionTraceContexts = ConcurrentHashMap<String, StompTraceContext>()

    override fun preSend(
        message: Message<*>,
        channel: MessageChannel,
    ): Message<*>? {
        val accessor = StompHeaderAccessor.wrap(message)
        previousSendMdc.set(MDC.getCopyOfContextMap())
        traceContextFor(accessor).applyToMdc()
        logFrame(accessor)
        return message
    }

    override fun afterSendCompletion(
        message: Message<*>,
        channel: MessageChannel,
        sent: Boolean,
        ex: Exception?,
    ) {
        restorePreviousMdc(previousSendMdc)
    }

    override fun beforeHandle(
        message: Message<*>,
        channel: MessageChannel,
        handler: MessageHandler,
    ): Message<*>? {
        previousHandleMdc.set(MDC.getCopyOfContextMap())
        val accessor = StompHeaderAccessor.wrap(message)
        traceContextFor(accessor).applyToMdc()
        return message
    }

    override fun afterMessageHandled(
        message: Message<*>,
        channel: MessageChannel,
        handler: MessageHandler,
        ex: Exception?,
    ) {
        restorePreviousMdc(previousHandleMdc)
    }

    private fun logFrame(accessor: StompHeaderAccessor) {
        val command = accessor.command ?: return
        if (command !in LOGGED_COMMANDS) return

        log.info(
            "↔ STOMP {} destination={} sessionId={} user={}",
            command,
            accessor.destination ?: "-",
            accessor.sessionId ?: "-",
            accessor.user?.name ?: "-",
        )
    }

    private fun traceContextFor(accessor: StompHeaderAccessor): StompTraceContext {
        val explicitContext = StompTraceContext.fromNativeHeaders(accessor)
        val sessionId = accessor.sessionId
        if (explicitContext != null) {
            if (sessionId != null) {
                sessionTraceContexts[sessionId] = explicitContext
            }
            return explicitContext
        }

        val sessionContext = sessionId?.let { sessionTraceContexts[it] }
        if (sessionContext != null) {
            if (accessor.command == StompCommand.DISCONNECT) {
                sessionTraceContexts.remove(sessionId)
            }
            return sessionContext.nextSpan()
        }

        return StompTraceContext(traceId = randomTraceId())
    }

    private fun restorePreviousMdc(threadLocal: ThreadLocal<Map<String, String>?>) {
        val previous = threadLocal.get()
        threadLocal.remove()
        if (previous == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(previous)
        }
    }

    private data class StompTraceContext(
        val traceId: String,
        val spanId: String = randomSpanId(),
        val parentSpanId: String? = null,
        val flags: String = DEFAULT_FLAGS,
    ) {
        fun applyToMdc() {
            MDC.put(TraceIdFilter.MDC_KEY, traceId)
            MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, spanId)
            if (parentSpanId == null) {
                MDC.remove(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY)
            } else {
                MDC.put(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY, parentSpanId)
            }
            MDC.put(TraceIdFilter.MDC_TRACE_CONTEXT_KEY, TraceIdFilter.formatLogContext(traceId, spanId, parentSpanId))
        }

        fun nextSpan(): StompTraceContext =
            copy(spanId = randomSpanId())

        companion object {
            fun fromNativeHeaders(accessor: StompHeaderAccessor): StompTraceContext? =
                parseTraceparent(accessor.firstNativeHeader(TraceIdFilter.HEADER_TRACEPARENT))
                    ?: legacyTraceId(accessor.firstNativeHeader(TraceIdFilter.HEADER_TRACE_ID))?.let { traceId ->
                        StompTraceContext(traceId = traceId)
                    }

            private fun parseTraceparent(value: String?): StompTraceContext? {
                val match = value?.let { TRACEPARENT_REGEX.matchEntire(it.trim()) } ?: return null
                val traceId = match.groupValues[1]
                val parentSpanId = match.groupValues[2]
                val flags = match.groupValues[3]
                if (traceId.all { it == '0' } || parentSpanId.all { it == '0' }) return null
                return StompTraceContext(traceId = traceId, parentSpanId = parentSpanId, flags = flags)
            }

            private fun legacyTraceId(value: String?): String? =
                value
                    ?.lowercase()
                    ?.takeIf { TRACE_ID_REGEX.matches(it) && it.any { char -> char != '0' } }
        }
    }

    private companion object {
        val LOGGED_COMMANDS = setOf(StompCommand.CONNECT, StompCommand.SUBSCRIBE, StompCommand.SEND, StompCommand.DISCONNECT)
        const val DEFAULT_FLAGS = "01"
        val TRACEPARENT_REGEX = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
        val TRACE_ID_REGEX = Regex("^[0-9a-f]{32}$")

        fun StompHeaderAccessor.firstNativeHeader(name: String): String? =
            getFirstNativeHeader(name) ?: getFirstNativeHeader(name.lowercase())

        fun randomTraceId(): String =
            UUID.randomUUID().toString().replace("-", "")

        fun randomSpanId(): String =
            UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
