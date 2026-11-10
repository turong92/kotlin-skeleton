package dev.sumin.skeleton.persistence.jooq

import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates

class JooqNativeHintsTest {
    @Test
    fun `the array classes of the data type constants are registered - without them SQLDataType fails to initialise in a native image`() {
        val hints = RuntimeHints()
        JooqNativeHints().registerHints(hints, javaClass.classLoader)

        // 이미지 안에서 Array.newInstance(타입, 0) 이 이 클래스들을 돌려줘야 한다
        listOf(Array<String>::class.java, Array<Long>::class.java, Array<java.time.OffsetDateTime>::class.java, Array<java.math.BigDecimal>::class.java, Array<ByteArray>::class.java).forEach { type ->
            assertTrue(RuntimeHintsPredicates.reflection().onType(type).test(hints), "${type.simpleName} must be registered")
        }
    }
}
