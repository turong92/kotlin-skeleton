package dev.sumin.skeleton.notification.broadcast.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.broadcast.BroadcastProperties
import dev.sumin.skeleton.notification.broadcast.BroadcastStepRunner
import dev.sumin.skeleton.notification.broadcast.BroadcastStore
import dev.sumin.skeleton.notification.broadcast.BroadcastTasks
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * 공지 저장소 · 작업 포트를 JDBC(PostgreSQL · MySQL)로 구현하고, 단계 처리기 · DEAD 고리를 `job-queue-jdbc` 에 얹는다. **공지를 켠 앱에서만 선다**
 * (`skeleton.notification.broadcast.enabled=true`) - 꺼진 앱은 이 모듈이 없는 앱과 같다(큐가 꺼져 있어도 시작이 실패하지 않는다).
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261102110000__broadcasts.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 * **이 클래스의 이름을 `notification-broadcast` 의 `BroadcastAutoConfiguration` 이 문자열로 가리킨다.** 큐가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다.
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class, JsonAutoConfiguration::class, NotificationAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
@ConditionalOnProperty(prefix = "skeleton.notification.broadcast", name = ["enabled"], havingValue = "true")
class BroadcastJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BroadcastStore::class)
    fun jdbcBroadcastStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): BroadcastStore =
        JdbcBroadcastStore(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), dialect)

    @Bean
    @ConditionalOnMissingBean(BroadcastTasks::class)
    fun jdbcBroadcastTasks(queue: JobQueue, json: JsonCodec, properties: BroadcastProperties): BroadcastTasks =
        JdbcBroadcastTasks(queue, json, properties.stepMaxAttempts)

    /** 큐 워커가 `JobHandler` 빈을 워커가 만들어질 때 모은다 - 러너는 늦게 찾는다(빈 순환을 피한다) */
    @Bean
    @ConditionalOnMissingBean(BroadcastStepJobHandler::class)
    fun broadcastStepJobHandler(json: JsonCodec, runner: ObjectProvider<BroadcastStepRunner>): BroadcastStepJobHandler =
        BroadcastStepJobHandler(json) { runner.getObject() }

    @Bean
    @ConditionalOnMissingBean(BroadcastDeadListener::class)
    fun broadcastDeadListener(json: JsonCodec, runner: ObjectProvider<BroadcastStepRunner>): BroadcastDeadListener =
        BroadcastDeadListener(json) { runner.getObject() }
}
