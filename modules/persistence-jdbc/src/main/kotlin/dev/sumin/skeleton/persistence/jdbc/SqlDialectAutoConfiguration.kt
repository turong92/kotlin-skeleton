package dev.sumin.skeleton.persistence.jdbc

import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

@AutoConfiguration(afterName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
@ConditionalOnBean(DataSource::class)
class SqlDialectAutoConfiguration {
    @Bean
    fun sqlDialectVerifier(dataSource: DataSource, dialects: ObjectProvider<SqlDialect>) =
        SqlDialectVerifier(dataSource, dialects.orderedStream().toList())
}
