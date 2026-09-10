package dev.sumin.skeleton.persistence.jdbc

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions

/**
 * `Instant` / `LocalDate` / `LocalDateTime` ↔ MySQL 왕복을 JVM 기본 시간대와 무관하게 고정한다.
 * DB 세션 UTC 는 [JdbcTimeZoneEnvironmentPostProcessor] 가 드라이버 속성으로 강제한다 (URL 파라미터 불필요).
 * 앱이 자체 `JdbcCustomConversions` 빈을 만들면 [UtcInstantConversions.all] 을 포함시켜야 한다.
 */
@AutoConfiguration
class JdbcUtcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jdbcCustomConversions(): JdbcCustomConversions = JdbcCustomConversions(UtcInstantConversions.all)
}
