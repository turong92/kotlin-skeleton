package dev.sumin.skeleton.notification.scheduled.jdbc

import dev.sumin.skeleton.notification.scheduled.ScheduledPushStore
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

/**
 * 예약 저장소([ScheduledPushStore])를 JDBC(PostgreSQL · MySQL)로 구현한다. **예약 푸시를 켠 앱에서만 선다**(`skeleton.notification.scheduled.enabled=true`).
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261115230000__scheduled_pushes.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 * **이 클래스의 이름을 `notification-scheduled` 의 `ScheduledPushAutoConfiguration` 이 문자열로 가리킨다.**
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "skeleton.notification.scheduled", name = ["enabled"], havingValue = "true")
class ScheduledPushJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ScheduledPushStore::class)
    fun jdbcScheduledPushStore(dataSource: DataSource, dialect: SqlDialect): ScheduledPushStore = JdbcScheduledPushStore(NamedParameterJdbcTemplate(dataSource), dialect)
}
