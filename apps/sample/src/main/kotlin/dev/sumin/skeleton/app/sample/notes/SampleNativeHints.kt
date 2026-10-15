package dev.sumin.skeleton.app.sample.notes

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지: 이 앱의 Bean Validation 검증기는 Hibernate Validator 가 이름으로 만들고 스프링이 코틀린 생성자를 kotlin-reflect 로 읽는다 — 스프링 빈이 아니라 AOT 가 모른다 (docs/native-image.md "리플렉션 규칙" 3).
 * 없으면 첨부가 달린 노트 저장이 `KotlinReflectionInternalError: Unresolved class` 로 500. `SampleApplication` 의 `@ImportRuntimeHints` 로 건다 — JVM 에서는 아무 일도 하지 않는다.
 */
class SampleNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(
            OwnAttachmentKeyValidator::class.java,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS,
        )
    }
}
