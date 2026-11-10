package dev.sumin.skeleton.persistence.jooq

import org.jooq.Record
import org.springframework.aot.hint.MemberCategory
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * 앱 패키지(`AutoConfigurationPackages`) 안의 jOOQ 레코드 클래스(코드 생성이 만든 `XxxRecord` 포함)의 생성자를 네이티브 이미지에 등록한다 (docs/native-image.md "jOOQ").
 * jOOQ 는 `TableImpl.getRecordConstructor()` 에서 `recordType.getDeclaredConstructor()` 를 리플렉션으로 불러 레코드를 만든다 —
 * 등록하지 않으면 `Could not construct new record` (`NoSuchMethodException: <Record>.<init>()`) 로 쓰기 · 읽기가 500 이다.
 * 앱이 따로 표시하지 않아도 되게, 빌드 때(AOT) 앱 패키지를 훑어 찾는다. JVM 실행에는 아무 일도 하지 않는다.
 */
class JooqRecordAotProcessor : BeanFactoryInitializationAotProcessor {
    override fun processAheadOfTime(beanFactory: ConfigurableListableBeanFactory): BeanFactoryInitializationAotContribution? {
        if (!AutoConfigurationPackages.has(beanFactory)) return null
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply { addIncludeFilter(AssignableTypeFilter(Record::class.java)) }
        val classLoader = beanFactory.beanClassLoader
        val types = AutoConfigurationPackages.get(beanFactory)
            .flatMap { scanner.findCandidateComponents(it) }
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it, false, classLoader) }
        if (types.isEmpty()) return null
        return BeanFactoryInitializationAotContribution { context, _ ->
            types.forEach { context.runtimeHints.reflection().registerType(it, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS) }
        }
    }
}
