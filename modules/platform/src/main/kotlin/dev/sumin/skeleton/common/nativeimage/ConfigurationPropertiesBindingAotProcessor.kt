package dev.sumin.skeleton.common.nativeimage

import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 모든 `@ConfigurationProperties` 클래스(와 그 안의 중첩 타입 · 맵 값 타입)를 네이티브 이미지에 리플렉션으로 등록한다 (docs/native-image.md "리플렉션 규칙").
 * 스프링 부트가 바인딩 타입을 등록해 주지만 코틀린의 **기본 인자가 있는 data class 중첩 타입**(예: `StorageWebProperties`, 소셜 제공자 맵의 값)은 생성자를
 * kotlin-reflect 가 못 찾아 `Could not compute caller for function <init>` 으로 기동이 죽었다. 이 처리기는 JVM 실행에는 아무 일도 하지 않는다 —
 * `META-INF/spring/aot.factories` 로 등록, 빌드 때(processAot) 설정 클래스 빈을 훑는다.
 */
class ConfigurationPropertiesBindingAotProcessor : BeanFactoryInitializationAotProcessor {
    override fun processAheadOfTime(beanFactory: ConfigurableListableBeanFactory): BeanFactoryInitializationAotContribution? {
        val types = beanFactory.getBeanNamesForAnnotation(ConfigurationProperties::class.java).mapNotNull { beanFactory.getType(it) }.distinct()
        if (types.isEmpty()) return null
        return BeanFactoryInitializationAotContribution { context, _ ->
            val registrar = BindingReflectionHintsRegistrar()
            types.forEach { registrar.registerReflectionHints(context.runtimeHints.reflection(), it) }
        }
    }
}
