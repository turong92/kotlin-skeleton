package dev.sumin.skeleton.dashboard.jdbc

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.dashboard.DashboardAutoConfiguration
import dev.sumin.skeleton.dashboard.DashboardDailyStore
import dev.sumin.skeleton.dashboard.DashboardLayoutStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
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
 * 대시보드의 저장 포트(일별 집계 · 화면 설정)를 JDBC(PostgreSQL · MySQL)로 채운다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261026150000__dashboard.sql`
 * (표 둘)을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다. 앱이 같은 포트의 빈을 두면 이쪽이 물러난다.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [DashboardAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class DashboardJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DashboardDailyStore::class)
    fun jdbcDashboardDailyStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect, time: ObjectProvider<TimeProvider>): DashboardDailyStore =
        JdbcDashboardDailyStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean(DashboardLayoutStore::class)
    fun jdbcDashboardLayoutStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect, time: ObjectProvider<TimeProvider>): DashboardLayoutStore =
        JdbcDashboardLayoutStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect, time.getIfAvailable { TimeProvider.systemUtc() })

    @Bean
    @ConditionalOnMissingBean(name = ["dashboardLayoutErasure"])
    fun dashboardLayoutErasure(layouts: DashboardLayoutStore): AccountErasureListener = DashboardLayoutErasure(layouts)
}
