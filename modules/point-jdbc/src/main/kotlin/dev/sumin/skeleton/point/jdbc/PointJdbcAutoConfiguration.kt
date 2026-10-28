package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.point.PointAutoConfiguration
import dev.sumin.skeleton.point.PointStore
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
 * 포인트 저장 포트를 JDBC(PostgreSQL · MySQL)로 채운다. 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261028140512__point.sql`(표 둘)을
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
}
