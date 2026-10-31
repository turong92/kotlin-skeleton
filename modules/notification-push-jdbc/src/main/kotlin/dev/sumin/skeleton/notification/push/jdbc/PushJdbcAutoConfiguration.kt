package dev.sumin.skeleton.notification.push.jdbc

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.json.JsonAutoConfiguration
import dev.sumin.skeleton.json.JsonCodec
import dev.sumin.skeleton.notification.push.PushAdvertisingNoticeStore
import dev.sumin.skeleton.notification.push.PushDeliveryWorker
import dev.sumin.skeleton.notification.push.PushDeviceStore
import dev.sumin.skeleton.notification.push.PushProperties
import dev.sumin.skeleton.notification.push.PushTasks
import dev.sumin.skeleton.notification.push.PushTypeMuteStore
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

/**
 * 기기 저장소 · 「푸시만 끄기」 저장소 · 재확인 안내 표시 저장소 · 작업 포트를 JDBC(PostgreSQL · MySQL)로 구현하고, 발송 처리기를 `job-queue-jdbc` 에 얹는다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261031130000__push_devices.sql` · `V20261031130100__push_type_mutes.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.notification.push.jdbc.PushJdbcAutoConfiguration`)을 `notification-push` 의 `PushAutoConfiguration` 이 문자열로 가리킨다** - 그쪽은 저장소 빈이 있어야 뜬다.
 * 큐(`job-queue-jdbc`)가 꺼져 있으면(`skeleton.job-queue.enabled=false`) 시작이 실패하고 `JobQueue` 빈이 없다고 말한다 - 뒷일 없이 푸시를 약속할 수 없다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(
    after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class, JsonAutoConfiguration::class],
)
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class PushJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PushDeviceStore::class)
    fun jdbcPushDeviceStore(dataSource: DataSource, transactionManager: PlatformTransactionManager, dialect: SqlDialect): PushDeviceStore =
        JdbcPushDeviceStore(NamedParameterJdbcTemplate(dataSource), transactionManager, dialect)

    @Bean
    @ConditionalOnMissingBean(PushTypeMuteStore::class)
    fun jdbcPushTypeMuteStore(dataSource: DataSource, dialect: SqlDialect): PushTypeMuteStore =
        JdbcPushTypeMuteStore(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(PushAdvertisingNoticeStore::class)
    fun jdbcPushAdvertisingNoticeStore(dataSource: DataSource, dialect: SqlDialect): PushAdvertisingNoticeStore =
        JdbcPushAdvertisingNoticeStore(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(PushTasks::class)
    fun jdbcPushTasks(queue: JobQueue, json: JsonCodec, properties: ObjectProvider<PushProperties>): PushTasks =
        JdbcPushTasks(queue, json, properties.getIfAvailable()?.maxAttempts)

    /** 큐 워커가 `JobHandler` 빈을 워커가 만들어질 때 모은다 - 처리기는 늦게 찾는다(빈 순환을 피한다) */
    @Bean
    @ConditionalOnMissingBean(PushJobHandler::class)
    fun pushJobHandler(json: JsonCodec, worker: ObjectProvider<PushDeliveryWorker>): PushJobHandler = PushJobHandler(json) { worker.getObject() }

    @Bean
    @ConditionalOnMissingBean(name = ["pushErasureListener"])
    fun pushErasureListener(dataSource: DataSource): AccountErasureListener = PushErasureListener(NamedParameterJdbcTemplate(dataSource))
}
