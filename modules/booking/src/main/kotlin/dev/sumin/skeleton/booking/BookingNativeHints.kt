package dev.sumin.skeleton.booking

import dev.sumin.skeleton.booking.web.ReadyEvent
import dev.sumin.skeleton.booking.web.SeatEvent
import dev.sumin.skeleton.booking.web.SeatsEvent
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지: 배치도 흐름의 사건 본문(`ready` · `seats`)은 `SseEmitter.send(...)` 로 컨트롤러 밖에서 객체째 직렬화되어 스프링 AOT 가 모른다 —
 * 없으면 흐름을 열 때 `Unresolved class` 로 실패한다 (docs/native-image.md "리플렉션 규칙", 선례 `NotificationSseNativeHints`).
 * `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다.
 */
class BookingNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        val registrar = BindingReflectionHintsRegistrar()
        listOf(ReadyEvent::class.java, SeatsEvent::class.java, SeatEvent::class.java, SeatState::class.java)
            .forEach { registrar.registerReflectionHints(hints.reflection(), it) }
    }
}
