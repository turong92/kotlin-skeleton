package dev.sumin.skeleton.auth.api

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지: Bean Validation 의 검증기는 Hibernate Validator 가 이름으로 만들고 스프링이 코틀린 생성자를 kotlin-reflect 로 읽는다 —
 * 스프링 빈이 아니라 AOT 가 모르므로 여기서 등록한다 (docs/native-image.md "리플렉션 규칙"). 없으면 로그인이 `KotlinReflectionInternalError: Unresolved class` 로 500.
 * `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다.
 */
class AuthNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(RequiredLoginIdentifierValidator::class.java, *KOTLIN_BEAN)
    }

    private companion object {
        val KOTLIN_BEAN = arrayOf(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.ACCESS_DECLARED_FIELDS)
    }
}
