package dev.sumin.skeleton.common.nativeimage

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지에서 kotlin-reflect 가 돌려면 필요한 것(docs/native-image.md). `EnvironmentPostProcessor` 처럼 스프링 컨텍스트가 서기 전에
 * 코틀린 생성자를 읽는 곳(예: `DatabaseStartupWaitPostProcessor`)은 `kotlin.ParameterName` 을 이름으로 찾는데, 이 어노테이션은 코드 어디에서도 쓰이지 않아 이미지에서 빠진다.
 * `META-INF/spring/aot.factories` 로 등록한다 — JVM 실행에는 아무 일도 하지 않는다.
 */
class KotlinReflectionHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(kotlin.ParameterName::class.java, MemberCategory.INVOKE_DECLARED_METHODS)
        hints.proxies().registerJdkProxy(kotlin.ParameterName::class.java)
        // `emptyMap()` · `emptyList()` · `emptySet()` 이 응답에 실려 Jackson 이 직렬화하면, jackson-module-kotlin 이 이 stdlib 싱글턴의 코틀린 정보를 읽는다
        // (없으면 `Unresolved class: kotlin.collections.EmptyMap` 으로 500). 코틀린 stdlib 클래스라 앱이 따로 알 수 없어 여기서 한 번 등록한다.
        listOf("kotlin.collections.EmptyMap", "kotlin.collections.EmptyList", "kotlin.collections.EmptySet").forEach {
            hints.reflection().registerTypeIfPresent(classLoader, it, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS, MemberCategory.INVOKE_DECLARED_METHODS)
        }
    }
}
