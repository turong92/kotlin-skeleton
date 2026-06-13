package dev.sumin.skeleton.persistence.jpa

import jakarta.persistence.EntityManager
import java.lang.reflect.Proxy
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class JpaPersistenceAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JpaPersistenceAutoConfiguration::class.java))
        .withBean(EntityManager::class.java, Supplier { entityManagerProxy() })

    @Test
    fun `creates jpa operational helper beans when entity manager exists`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeansOfType(JpaPartialUpdateExecutor::class.java).size)
            assertEquals(1, context.getBeansOfType(JpaFetchGraphHints::class.java).size)
        }
    }

    @Test
    fun `backs off user provided helper beans`() {
        val customPartialUpdateExecutor = JpaPartialUpdateExecutor(entityManagerProxy())
        val customFetchGraphHints = JpaFetchGraphHints(entityManagerProxy())

        contextRunner
            .withBean(JpaPartialUpdateExecutor::class.java, Supplier { customPartialUpdateExecutor })
            .withBean(JpaFetchGraphHints::class.java, Supplier { customFetchGraphHints })
            .run { context ->
                assertEquals(customPartialUpdateExecutor, context.getBean(JpaPartialUpdateExecutor::class.java))
                assertEquals(customFetchGraphHints, context.getBean(JpaFetchGraphHints::class.java))
            }
    }

    private fun entityManagerProxy(): EntityManager =
        Proxy.newProxyInstance(
            EntityManager::class.java.classLoader,
            arrayOf(EntityManager::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as EntityManager
}
