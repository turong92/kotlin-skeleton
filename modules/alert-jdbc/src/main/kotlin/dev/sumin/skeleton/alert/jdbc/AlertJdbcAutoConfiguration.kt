package dev.sumin.skeleton.alert.jdbc

import dev.sumin.skeleton.alert.AlertAutoConfiguration
import dev.sumin.skeleton.alert.AlertStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager

/**
 * `alert` 의 메모리 저장소를 DB 로 바꾼다 — 여러 인스턴스 · 재시작을 가로질러 접고 목록을 남긴다. `skeleton.alert-jdbc.enabled=false` 로 끈다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261005181125__alerts.sql`(앱의 `spring.flyway.locations=classpath:db/migration/{vendor}`)이다.
 */
@AutoConfiguration(
    before = [AlertAutoConfiguration::class],
    after = [JdbcClientAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
)
@ConditionalOnBean(DataSource::class, JdbcClient::class)
@ConditionalOnProperty(prefix = "skeleton.alert-jdbc", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AlertJdbcProperties::class)
class AlertJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(AlertStore::class)
    fun jdbcAlertStore(
        jdbc: JdbcClient,
        transactionManager: PlatformTransactionManager,
        dialect: SqlDialect,
        properties: AlertJdbcProperties,
    ): JdbcAlertStore = JdbcAlertStore(jdbc, transactionManager, dialect, properties.retention)
}
