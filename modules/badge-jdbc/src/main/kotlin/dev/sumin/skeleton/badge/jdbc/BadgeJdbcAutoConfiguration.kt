package dev.sumin.skeleton.badge.jdbc

import dev.sumin.skeleton.badge.BadgeStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * 배지 저장([BadgeStore])을 JDBC(PostgreSQL · MySQL)로 구현한다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261029100000__badges.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.badge.jdbc.BadgeJdbcAutoConfiguration`)을 `badge` 의 `BadgeAutoConfiguration` 이 문자열로 가리킨다** - 그쪽은 저장소 빈이 있어야 켜진다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class BadgeJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BadgeStore::class)
    fun jdbcBadgeStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): BadgeStore =
        JdbcBadgeStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect)
}
