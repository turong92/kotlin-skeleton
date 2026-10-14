package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.project.ProjectRegistry
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration(afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
@EnableConfigurationProperties(JdbcStartupWaitProperties::class)   // 키 · 기본값을 설정 메타데이터에 올린다 (읽는 것은 DatabaseStartupWaitPostProcessor 가 컨텍스트 전에)
class SqlDialectAutoConfiguration {
    @Bean
    fun sqlDialectVerifier(dataSource: DataSource, dialects: ObjectProvider<SqlDialect>, environment: Environment, details: ObjectProvider<JdbcConnectionDetails>) =
        if (ProjectRegistry.configured(environment)) {
            SqlDialectVerifier(requireNotNull(details.getIfAvailable()?.jdbcUrl ?: environment.getProperty("spring.datasource.url")) { "spring.datasource.url is required with skeleton.projects" }, dialects.orderedStream().toList())
        } else {
            SqlDialectVerifier(dataSource, dialects.orderedStream().toList())
        }
}
