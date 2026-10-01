package dev.sumin.skeleton.persistence.mysql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

/** `modules:db-mysql` 을 끼우면 SqlDialect = MySQL, 세션 UTC 강제는 [MySqlTimeZoneEnvironmentPostProcessor]. */
@AutoConfiguration
class MySqlDialectAutoConfiguration {
    @Bean
    fun mySqlSqlDialect(): SqlDialect = MySqlSqlDialect()

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcCustomConversions::class)
    class DataJdbcConversions {
        /** 앱이 자기 JdbcCustomConversions 를 만들면 [MySqlTimeConversions.all] 을 포함시켜야 한다 */
        @Bean
        @ConditionalOnMissingBean
        fun jdbcCustomConversions(): JdbcCustomConversions = JdbcCustomConversions(MySqlTimeConversions.all)
    }
}
