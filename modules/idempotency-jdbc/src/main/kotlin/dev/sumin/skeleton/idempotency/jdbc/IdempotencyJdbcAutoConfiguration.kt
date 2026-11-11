package dev.sumin.skeleton.idempotency.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.idempotency.IdempotencyAutoConfiguration
import dev.sumin.skeleton.idempotency.IdempotencyProperties
import dev.sumin.skeleton.idempotency.IdempotencyStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
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
 * 멱등 키 저장([IdempotencyStore])을 JDBC(PostgreSQL · MySQL)로 구현한다 - 인스턴스가 여럿이거나 다시 떠도 같은 키가 한 번만 실행된다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261111100000__idempotency_keys.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 * `idempotency` 의 기본 인메모리 저장소보다 먼저 서야 한다(`@ConditionalOnMissingBean`) - [IdempotencyAutoConfiguration] 이 이 클래스 뒤에 온다.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class],
    before = [IdempotencyAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "skeleton.idempotency", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class IdempotencyJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(IdempotencyStore::class)
    fun jdbcIdempotencyStore(
        dataSource: DataSource,
        dialect: SqlDialect,
        time: ObjectProvider<TimeProvider>,
        properties: ObjectProvider<IdempotencyProperties>,
    ): JdbcIdempotencyStore =
        JdbcIdempotencyStore(
            NamedParameterJdbcTemplate(dataSource), dialect, time.getIfAvailable { TimeProvider.systemUtc() },
            properties.getIfAvailable { IdempotencyProperties() }.inProgressLease,
        )

    /** `scheduler` 모듈이 클래스패스에 있을 때만 - 없으면 이 표시를 읽는 쪽이 없다 */
    @Bean
    @ConditionalOnBean(JdbcIdempotencyStore::class)
    @ConditionalOnClass(name = ["dev.sumin.skeleton.scheduler.SkeletonScheduled"])
    @ConditionalOnMissingBean
    fun idempotencyKeyPurge(store: JdbcIdempotencyStore): IdempotencyKeyPurge = IdempotencyKeyPurge(store)

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - 만료한 줄을 지우는 일은 멱등이다 */
    @Bean
    @ConditionalOnBean(JdbcIdempotencyStore::class)
    @ConditionalOnMissingBean(name = ["idempotencyPurgeTask"])
    fun idempotencyPurgeTask(store: JdbcIdempotencyStore): OnRequestTask = SimpleOnRequestTask("idempotency-purge") { store.purgeExpired() }
}
