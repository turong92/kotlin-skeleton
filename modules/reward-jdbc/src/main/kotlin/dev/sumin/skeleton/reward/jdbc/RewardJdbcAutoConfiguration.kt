package dev.sumin.skeleton.reward.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.reward.RewardGrantStore
import dev.sumin.skeleton.reward.RewardProperties
import dev.sumin.skeleton.reward.RewardTasks
import dev.sumin.skeleton.reward.RewardWorker
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

/**
 * 지급 건 저장([RewardGrantStore])과 작업 포트([RewardTasks])를 JDBC(PostgreSQL · MySQL)로 구현하고, 일꾼을 `job-queue-jdbc` 에 얹는다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261028140600__reward_grants.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.reward.jdbc.RewardJdbcAutoConfiguration`)을 `reward` 의 `RewardAutoConfiguration` 이 문자열로 가리킨다** - 그쪽은 저장소 빈이 있어야 켜진다.
 * 큐(`job-queue-jdbc`)가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다 - 뒷일 없이 지급을 약속할 수 없다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class, JsonAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class RewardJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(RewardGrantStore::class)
    fun jdbcRewardGrantStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect, json: JsonCodec): RewardGrantStore =
        JdbcRewardGrantStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect, json)

    @Bean
    @ConditionalOnMissingBean(RewardTasks::class)
    fun jdbcRewardTasks(queue: JobQueue, store: RewardGrantStore, properties: RewardProperties): RewardTasks =
        JdbcRewardTasks(queue, store, properties.worker.maxAttempts)

    /** 큐 워커가 `JobHandler` 빈을 워커가 만들어질 때 모은다 - 일꾼은 늦게 찾는다(처리기가 이 빈들을 쓰는 순환을 피한다) */
    @Bean
    @ConditionalOnMissingBean(RewardJobHandler::class)
    fun rewardJobHandler(worker: ObjectProvider<RewardWorker>): RewardJobHandler = RewardJobHandler { worker.getObject() }

    @Bean
    @ConditionalOnMissingBean(RewardDeadListener::class)
    fun rewardDeadListener(store: ObjectProvider<RewardGrantStore>): RewardDeadListener = RewardDeadListener { store.getObject() }
}
