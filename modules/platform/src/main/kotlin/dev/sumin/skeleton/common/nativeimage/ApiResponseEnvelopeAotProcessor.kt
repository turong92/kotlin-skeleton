package dev.sumin.skeleton.common.nativeimage

import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.util.ReflectionUtils

/**
 * `@ApiResponseEnvelope(value = X::class)` 이 붙은 컨트롤러 메서드의 응답 타입 `X` 를 네이티브 이미지에 JSON 바인딩용으로 등록한다 (docs/native-image.md "리플렉션 규칙").
 * 스프링 AOT 는 메서드의 **반환 타입**에서 JSON 타입을 찾는데, 이 레포의 컨트롤러 일부는 `Any` 를 돌려주고(`Response.ok(...)`) 실제 타입은 이 어노테이션에만 적혀 있다 —
 * 없으면 `KotlinReflectionInternalError: Unresolved class` 로 500 이다 (실측: 저장소 presign). 이 처리기가 어노테이션을 읽어 한 번에 등록하므로 컨트롤러마다 따로 표시하지 않는다.
 * JVM 실행에는 아무 일도 하지 않는다.
 */
class ApiResponseEnvelopeAotProcessor : BeanFactoryInitializationAotProcessor {
    override fun processAheadOfTime(beanFactory: ConfigurableListableBeanFactory): BeanFactoryInitializationAotContribution? {
        val types = linkedSetOf<Class<*>>()
        for (name in beanFactory.beanDefinitionNames) {
            val beanType = beanFactory.getType(name, false) ?: continue
            ReflectionUtils.doWithMethods(beanType) { method ->
                val envelope = AnnotatedElementUtils.findMergedAnnotation(method, ApiResponseEnvelope::class.java) ?: return@doWithMethods
                val value = envelope.value.java
                if (value != Unit::class.java && value != Void.TYPE) types += value
            }
        }
        if (types.isEmpty()) return null
        return BeanFactoryInitializationAotContribution { context, _ ->
            val registrar = BindingReflectionHintsRegistrar()
            types.forEach { registrar.registerReflectionHints(context.runtimeHints.reflection(), it) }
        }
    }
}
