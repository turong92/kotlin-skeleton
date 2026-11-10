package dev.sumin.skeleton.persistence.jooq

import dev.sumin.skeleton.persistence.jooq.generated.tables.records.JooqProbeRecord
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.aot.generate.ClassNameGenerator
import org.springframework.aot.generate.DefaultGenerationContext
import org.springframework.aot.generate.InMemoryGeneratedFiles
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.javapoet.ClassName

class JooqRecordAotProcessorTest {
    @Test
    fun `record classes in the application packages get their no-arg constructor registered - jOOQ builds records through it`() {
        val beanFactory = DefaultListableBeanFactory().also { AutoConfigurationPackages.register(it, "dev.sumin.skeleton.persistence.jooq.generated") }

        val contribution = JooqRecordAotProcessor().processAheadOfTime(beanFactory)

        assertNotNull(contribution)
        val hints = RuntimeHints()
        contribution.applyTo(DefaultGenerationContext(ClassNameGenerator(ClassName.get(javaClass)), InMemoryGeneratedFiles(), hints), Mockito.mock(BeanFactoryInitializationCode::class.java))
        assertTrue(RuntimeHintsPredicates.reflection().onConstructorInvocation(JooqProbeRecord::class.java.getDeclaredConstructor()).test(hints))
    }

    @Test
    fun `nothing is contributed when the application has no packages registered`() {
        assertNull(JooqRecordAotProcessor().processAheadOfTime(DefaultListableBeanFactory()))
    }
}
