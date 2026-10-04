package dev.sumin.skeleton.persistence.jdbc

import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.UnsatisfiedDependencyException

class SqlDialectFailureAnalyzerTest {
    private val analyzer = SqlDialectFailureAnalyzer()

    @Test
    fun `missing SqlDialect bean is explained with the modules to add`() {
        // 소비 빈(job-queue, notification)이 SqlDialectVerifier 보다 먼저 만들어지면 이 예외가 먼저 난다
        val failure = UnsatisfiedDependencyException(
            "JobQueueJdbcAutoConfiguration", "jdbcJobRepository", "dialect", NoSuchBeanDefinitionException(SqlDialect::class.java),
        )
        val analysis = assertNotNull(analyzer.analyze(failure))
        assertThat(analysis.description).contains("SqlDialect")
        assertThat(analysis.action).contains("modules:db-postgresql").contains("modules:db-mysql")
    }

    @Test
    fun `other missing beans are left to the default analyzers`() {
        assertNull(analyzer.analyze(NoSuchBeanDefinitionException(String::class.java)))
    }
}
