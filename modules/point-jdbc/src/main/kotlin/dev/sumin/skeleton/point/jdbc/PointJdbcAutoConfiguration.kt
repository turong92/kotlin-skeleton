package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.point.PointAutoConfiguration
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.point.PointOperationLog
import dev.sumin.skeleton.point.PointOperationScheduler
import dev.sumin.skeleton.point.PointOperations
import dev.sumin.skeleton.point.PointProperties
import dev.sumin.skeleton.point.PointStore
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
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
 * 포인트 저장 포트를 JDBC(PostgreSQL · MySQL)로 채운다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261028140512__point.sql`(표 둘) · `V20261106040000__point_spend_allocations.sql` · `V20261107000000__point_operations.sql` 을
 * 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. 앱이 같은 포트의 빈을 두면 이쪽이 물러난다.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [PointAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class PointJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PointStore::class)
    fun jdbcPointStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): PointStore =
        JdbcPointStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)

    /** 작업 기록(영수증 · 시도). 조회 예약기([PointOperationScheduler])는 `job-queue-jdbc` 가 있을 때 [PointOperationJobsAutoConfiguration] 이 낸다 - 없으면 조회는 앱이 `resolve` 를 부른다 */
    @Bean
    @ConditionalOnMissingBean(PointOperationLog::class)
    fun jdbcPointOperationLog(
        dataSource: DataSource,
        transactionManager: PlatformTransactionManager,
        dialect: SqlDialect,
        scheduler: ObjectProvider<PointOperationScheduler>,
        properties: PointProperties,
    ): PointOperationLog = JdbcPointOperationLog(
        NamedParameterJdbcTemplate(dataSource), transactionManager, dialect,
        scheduler = { scheduler.getIfAvailable() }, firstDelay = properties.operations.reconcile.firstDelay,
    )

    /** 기록되는 포인트 쓰기 - 지갑 위의 선택 층이다(앱이 안 써도 된다) */
    @Bean
    @ConditionalOnMissingBean(PointOperations::class)
    fun pointOperations(store: PointStore, log: PointOperationLog, transactionManager: PlatformTransactionManager, time: TimeProvider): PointOperations =
        JdbcPointOperations(store, log, transactionManager, time)
}
