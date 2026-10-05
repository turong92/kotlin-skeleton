package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.auth.sessions.AuthSessionAutoConfiguration
import dev.sumin.skeleton.auth.sessions.SessionStore
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
import org.springframework.transaction.support.TransactionTemplate

/**
 * 세션 저장소를 JDBC(PostgreSQL · MySQL)로. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261005220927__skeleton_auth_sessions.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. [AuthSessionAutoConfiguration] 보다 먼저 평가해 메모리 기본이 물러난다.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [AuthSessionAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class AuthSessionJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(SessionStore::class)
    fun jdbcSessionStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): SessionStore =
        JdbcSessionStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)
}
