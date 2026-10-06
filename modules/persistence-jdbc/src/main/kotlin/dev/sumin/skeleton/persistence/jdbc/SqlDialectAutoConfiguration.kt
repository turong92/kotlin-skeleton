package dev.sumin.skeleton.persistence.jdbc

import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
@EnableConfigurationProperties(JdbcStartupWaitProperties::class)   // 키 · 기본값을 설정 메타데이터에 올린다 (읽는 것은 DatabaseStartupWaitPostProcessor 가 컨텍스트 전에)
class SqlDialectAutoConfiguration {
    @Bean
    fun sqlDialectVerifier(dataSource: DataSource, dialects: ObjectProvider<SqlDialect>) =
        SqlDialectVerifier(dataSource, dialects.orderedStream().toList())
}
