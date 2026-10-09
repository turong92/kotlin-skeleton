package dev.sumin.skeleton.payment.toss.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * 운영과 같은 모양으로 줄을 모은다: 서드파티(스프링 · 넷티)는 운영 기본인 INFO, 이 레포의 코드(`dev.sumin`)는 DEBUG 까지 — 예외 메시지와 원인 포함.
 * 서드파티의 DEBUG/TRACE(스프링 코덱이 본문을 찍는다)는 운영자가 일부러 켜야 나오는 것이라 여기서는 보지 않는다.
 * 시험이 끝나면 [close] 로 원래대로 돌린다.
 */
internal class CapturedLogs : AutoCloseable {
    private val root: Logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME)
    private val previousLevel: Level? = root.level
    private val own: Logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("dev.sumin")
    private val previousOwnLevel: Level? = own.level
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        root.level = Level.INFO
        own.level = Level.DEBUG
        root.addAppender(appender)
    }

    /** 포맷된 메시지 + 예외 체인의 메시지, 한 줄에 하나. */
    fun lines(): List<String> =
        appender.list.toList().map { event ->
            buildString {
                append(event.formattedMessage)
                var proxy = event.throwableProxy
                while (proxy != null) {
                    append(" | ").append(proxy.className).append(": ").append(proxy.message)
                    proxy = proxy.cause
                }
                (event.throwableProxy as? ThrowableProxy)?.throwable?.let { append(" | ").append(it.stackTraceToString()) }
            }
        }

    override fun close() {
        root.detachAppender(appender)
        root.level = previousLevel
        own.level = previousOwnLevel
        appender.stop()
    }
}
