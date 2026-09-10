package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
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
import org.springframework.transaction.support.TransactionTemplate

/**
 * `skeleton.job-queue.enabled=false` 로 끈다. 스키마는 모듈 마이그레이션(`db/migration/V2026091001__skeleton_jobs.sql`)
 * 이 Flyway 기본 위치 재귀 스캔으로 적용된다. Flyway 를 안 쓰는 앱은 그 SQL 을 `schema.sql` 에 복사한다.
 */
@AutoConfiguration(after = [JdbcClientAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class])
@ConditionalOnBean(DataSource::class, JdbcClient::class)
@ConditionalOnProperty(prefix = "skeleton.job-queue", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JobQueueProperties::class)
class JobQueueJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jdbcJobRepository(jdbc: JdbcClient, transactionManager: PlatformTransactionManager) =
        JdbcJobRepository(jdbc, TransactionTemplate(transactionManager))

    @Bean
    @ConditionalOnMissingBean(JobQueue::class)
    fun jdbcJobQueue(repository: JdbcJobRepository, properties: JobQueueProperties, timeProvider: ObjectProvider<TimeProvider>): JobQueue =
        JdbcJobQueue(repository, properties, timeProvider.getIfAvailable { TimeProvider.systemUtc() })

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.job-queue", name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
    fun jobQueueWorker(
        repository: JdbcJobRepository,
        handlers: ObjectProvider<JobHandler>,
        properties: JobQueueProperties,
        timeProvider: ObjectProvider<TimeProvider>,
    ) = JobQueueWorker(repository, handlers.orderedStream().toList(), properties, timeProvider.getIfAvailable { TimeProvider.systemUtc() })
}
