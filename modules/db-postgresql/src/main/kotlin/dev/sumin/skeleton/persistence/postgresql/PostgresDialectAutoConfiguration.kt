package dev.sumin.skeleton.persistence.postgresql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

/** `modules:db-postgresql` 을 끼우면 SqlDialect = PostgreSQL. 다른 db-* 모듈과 같이 끼우면 SqlDialectVerifier 가 기동을 막는다. */
// jdbcCustomConversions 가 Boot 의 Data JDBC 자동설정(같은 @ConditionalOnMissingBean)보다 먼저 등록돼야 한다 — 이름 순서에 기대지 않고 명시
@AutoConfiguration(beforeName = ["org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration"])
class PostgresDialectAutoConfiguration {
    @Bean
    fun postgresSqlDialect(): SqlDialect = PostgresSqlDialect()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcCustomConversions::class)
    class DataJdbcConversions {
        /** 앱이 자기 JdbcCustomConversions 를 만들면 [PostgresTimeConversions.all] 을 포함시켜야 한다 */
        @Bean
        @ConditionalOnMissingBean
        fun jdbcCustomConversions(): JdbcCustomConversions = JdbcCustomConversions(PostgresTimeConversions.all)
    }
}
