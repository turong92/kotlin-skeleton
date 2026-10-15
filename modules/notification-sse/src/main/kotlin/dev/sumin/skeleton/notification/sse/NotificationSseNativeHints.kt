package dev.sumin.skeleton.notification.sse

import dev.sumin.skeleton.notification.NotificationEvent
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지: SSE 로 흘려보내는 이벤트 본문(접속 알림 · 알림 이벤트)은 `SseEmitter.send(...)` 로 객체째 직렬화되어 스프링 AOT 가 모른다 —
 * 없으면 연결 시작 시 `Unresolved class: NotificationSseConnectedEvent` 로 500 이다 (docs/native-image.md "리플렉션 규칙"). `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다.
 */
class NotificationSseNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        val registrar = BindingReflectionHintsRegistrar()
        listOf(NotificationSseConnectedEvent::class.java, NotificationEvent::class.java).forEach { registrar.registerReflectionHints(hints.reflection(), it) }
    }
}
