package dev.sumin.skeleton.persistence.mysql

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

/** `modules:db-mysql` 을 끼우면 SqlDialect = MySQL, 세션 UTC 강제는 [MySqlTimeZoneEnvironmentPostProcessor]. */
// jdbcCustomConversions 가 Boot 의 Data JDBC 자동설정(같은 @ConditionalOnMissingBean)보다 먼저 등록돼야 한다 — 이름 순서에 기대지 않고 명시
@AutoConfiguration(beforeName = ["org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration"])
class MySqlDialectAutoConfiguration {
    @Bean
    fun mySqlSqlDialect(): SqlDialect = MySqlSqlDialect()

    // @Configuration 이 아니다: @Bean 메서드가 있는 중첩 클래스는 AutoConfiguration 이 그대로 처리한다
    @ConditionalOnClass(JdbcCustomConversions::class)
    class DataJdbcConversions {
        /** 앱이 자기 JdbcCustomConversions 를 만들면 [MySqlTimeConversions.all] 을 포함시켜야 한다 */
        @Bean
        @ConditionalOnMissingBean
        fun jdbcCustomConversions(): JdbcCustomConversions = JdbcCustomConversions(MySqlTimeConversions.all)
    }
}
