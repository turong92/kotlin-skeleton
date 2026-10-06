package dev.sumin.skeleton.legal.jdbc

import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.LegalAutoConfiguration
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * `legal` 의 저장소 포트(동의 기록 · 판 장부)를 JDBC(PostgreSQL · MySQL)로 구현한다. 스키마는 모듈 마이그레이션
 * `db/migration/<vendor>/V20261006174411__legal.sql`(앱의 `spring.flyway.locations=classpath:db/migration/{vendor}`). 앱이 같은 타입의 빈을 두면 이쪽이 물러난다.
 */
@AutoConfiguration(
    before = [LegalAutoConfiguration::class],
    after = [JdbcClientAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
)
@ConditionalOnBean(DataSource::class, JdbcClient::class)
class LegalJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ConsentStore::class)
    fun jdbcConsentStore(jdbc: JdbcClient, dialect: SqlDialect): ConsentStore = JdbcConsentStore(jdbc, dialect)

    @Bean
    @ConditionalOnMissingBean(LegalLedger::class)
    fun jdbcLegalLedger(jdbc: JdbcClient, dialect: SqlDialect): LegalLedger = JdbcLegalLedger(jdbc, dialect)
}
