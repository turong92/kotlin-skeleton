package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountBlockStore
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.AccountTransaction
import dev.sumin.skeleton.account.challenge.ChallengeStore
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.token.OneTimeTokenStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 계정 저장소 · 토큰 저장소를 JDBC(PostgreSQL · MySQL)로, 그리고 (켜면) 감사 기록을. 스키마는 모듈 마이그레이션
 * `db/migration/<vendor>/V20261005223426__accounts.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 * [AccountAutoConfiguration] 보다 먼저 평가해 메모리 기본이 물러난다. 감사는 `skeleton.account.audit.enabled=true` 일 때만 쓴다.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [AccountAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class AccountJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountRepository::class)
    fun jdbcAccountRepository(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): AccountRepository =
        JdbcAccountRepository(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)

    /** 계정 만들기와 같이 가야 하는 쓰기(약관 동의 기록)를 한 DB 트랜잭션으로 묶는다 — 저장소들의 `TransactionTemplate` 이 이 트랜잭션에 합류한다 */
    @Bean
    @ConditionalOnMissingBean(AccountTransaction::class)
    fun jdbcAccountTransaction(transactionManager: PlatformTransactionManager): AccountTransaction = JdbcAccountTransaction(transactionManager)

    @Bean
    @ConditionalOnMissingBean(OneTimeTokenStore::class)
    fun jdbcOneTimeTokenStore(dataSource: DataSource, dialect: SqlDialect): OneTimeTokenStore = JdbcOneTimeTokenStore(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(ChallengeStore::class)
    fun jdbcChallengeStore(dataSource: DataSource, dialect: SqlDialect): ChallengeStore = JdbcChallengeStore(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(AccountBlockStore::class)
    fun jdbcAccountBlockStore(dataSource: DataSource, dialect: SqlDialect): AccountBlockStore = JdbcAccountBlockStore(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(name = ["jdbcAccountAuditListener"])
    @ConditionalOnProperty(prefix = "skeleton.account.audit", name = ["enabled"], havingValue = "true")
    fun jdbcAccountAuditListener(dataSource: DataSource, dialect: SqlDialect): AccountEventListener = JdbcAccountAuditListener(NamedParameterJdbcTemplate(dataSource), dialect)
}
