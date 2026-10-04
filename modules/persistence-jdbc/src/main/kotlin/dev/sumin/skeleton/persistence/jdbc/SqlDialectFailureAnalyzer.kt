package dev.sumin.skeleton.persistence.jdbc

import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * 방언 모듈을 안 끼웠을 때, SqlDialect 를 주입받는 빈이 [SqlDialectVerifier] 보다 먼저 만들어지면
 * "No qualifying bean of type SqlDialect" 만 보인다. 그 경우에도 같은 안내(어느 모듈을 끼울지)를 보여 준다.
 */
class SqlDialectFailureAnalyzer : AbstractFailureAnalyzer<NoSuchBeanDefinitionException>() {
    override fun analyze(rootFailure: Throwable, cause: NoSuchBeanDefinitionException): FailureAnalysis? {
        val missing = cause.beanType ?: cause.resolvableType?.resolve()
        if (missing != SqlDialect::class.java) return null
        return FailureAnalysis(
            "No SqlDialect bean: the app uses JDBC modules but assembles no database dialect module.",
            "Add exactly one of implementation(project(\":modules:db-postgresql\")) " +
                "or implementation(project(\":modules:db-mysql\")) to the app.",
            cause,
        )
    }
}
