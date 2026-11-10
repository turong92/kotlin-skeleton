package dev.sumin.skeleton.persistence.jooq

import org.jooq.DataType
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지에서 jOOQ(`DSLContext`)가 서도록 필요한 것만 알려 준다 (docs/native-image.md "jOOQ"). `META-INF/spring/aot.factories` 로 등록 - JVM 에서는 아무 일도 하지 않는다.
 *
 * `SQLDataType` 의 정적 초기화가 자료형마다 배열형(`Array.newInstance(타입, 0).getClass()`)을 만든다. 네이티브에서는 배열 클래스를 미리 알려 주지 않으면 null 이 나와
 * `ConcurrentHashMap.putIfAbsent` NPE 로 `DSLContext` 가 서지 못한다. 이미지를 만들 때(JVM) 자료형 상수들의 자바 타입을 훑어 그 배열형을 등록한다.
 * 방언별 자료형(`PostgresDataType` · `MySQLDataType`)은 jOOQ 본체 jar 에 들어 있고 `SQLDataType` 의 초기화가 그 클래스들을 모두 불러온다 - 그 상수들이 더하는 자바 타입은 `SQLDataType` 것과 겹쳐
 * 추가로 등록할 배열형이 없다. 그래서 `SQLDataType` 하나만 훑으면 충분하다.
 */
class JooqNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        arrayTypesOf(classLoader ?: javaClass.classLoader).forEach { hints.reflection().registerType(it) }
    }

    internal companion object {
        /** `SQLDataType` 상수들의 자바 타입 → 그 배열형. 클래스를 못 불러오거나 초기화가 실패하면 그대로 던진다(조용히 힌트가 비면 네이티브에서야 드러난다) */
        fun arrayTypesOf(classLoader: ClassLoader): Set<Class<*>> =
            Class.forName("org.jooq.impl.SQLDataType", true, classLoader).fields
                .filter { DataType::class.java.isAssignableFrom(it.type) }
                .map { java.lang.reflect.Array.newInstance((it.get(null) as DataType<*>).type, 0).javaClass }
                .toCollection(linkedSetOf())
    }
}
