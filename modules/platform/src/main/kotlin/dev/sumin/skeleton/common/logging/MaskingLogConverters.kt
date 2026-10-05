package dev.sumin.skeleton.common.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import java.util.function.UnaryOperator
import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer

/** `dev/sumin/skeleton/logging/logback-masking.xml` 이 `%m` 으로 건다 — 사람이 읽는 줄 로그의 메시지를 [LogMasker] 로 거른다 */
class MaskingMessageConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = LogMasker.current.mask(event.formattedMessage ?: "")
}

/** `%wEx` 자리 — 스택 트레이스 속 예외 메시지(DB 제약 위반의 값 등)도 거른다 */
class MaskingThrowableConverter : ExtendedWhitespaceThrowableProxyConverter() {
    override fun throwableProxyToString(tp: IThrowableProxy): String = LogMasker.current.mask(super.throwableProxyToString(tp))
}

/**
 * 구조 로그(`logging.structured.json.customizer`) — JSON 의 문자열 값(메시지 · 스택 · MDC)을 전부 [LogMasker] 로 거른다.
 * 키(필드 이름)는 건드리지 않는다
 */
class MaskingStructuredLoggingCustomizer : StructuredLoggingJsonMembersCustomizer<Any> {
    override fun customize(members: JsonWriter.Members<Any>) {
        members.applyingValueProcessor(
            JsonWriter.ValueProcessor.of<String>(String::class.java, UnaryOperator<String?> { it?.let(LogMasker.current::mask) }),
        )
    }
}
