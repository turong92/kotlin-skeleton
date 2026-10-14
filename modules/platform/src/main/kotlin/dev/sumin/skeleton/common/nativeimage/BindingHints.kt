package dev.sumin.skeleton.common.nativeimage

import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.RuntimeHints

/**
 * 컨트롤러 밖에서 JSON 으로 읽고 쓰는 타입(외부 API 응답 · 웹훅 본문 · 저장하는 JSON)을 네이티브 이미지에 등록한다 — Jackson 이 생성자 · 필드를 리플렉션으로 쓰고 kotlin-reflect 가 코틀린 정보를 읽는다
 * (docs/native-image.md "리플렉션 규칙"). 클래스가 비공개(private)라 어노테이션(`@RegisterReflectionForBinding`)에 못 적을 때 이름으로 건다 — 없으면 건너뛴다(선택 의존성).
 * 중첩 타입(필드의 타입)도 함께 등록된다.
 */
object BindingHints {
    fun register(hints: RuntimeHints, classLoader: ClassLoader?, vararg classNames: String) {
        val registrar = BindingReflectionHintsRegistrar()
        classNames.forEach { name ->
            val type = runCatching { Class.forName(name, false, classLoader ?: BindingHints::class.java.classLoader) }.getOrNull() ?: return@forEach
            registrar.registerReflectionHints(hints.reflection(), type)
        }
    }
}
