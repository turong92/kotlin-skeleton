package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.video.DisplayJobStore
import dev.sumin.skeleton.video.VideoFinalizer
import dev.sumin.skeleton.video.VideoFollowUps
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoTransactions
import dev.sumin.skeleton.video.VideoWakeRunner
import dev.sumin.skeleton.video.VideoWakeRuns
import dev.sumin.skeleton.video.VideoWorkerStore
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
 * 영상 저장([VideoStore] · [VideoWorkerStore])을 JDBC(PostgreSQL · MySQL)로 구현하고, 후속 작업(`video.finalize` · `video.wake`)을 `job-queue-jdbc` 에 얹는다.
 * 스키마는 모듈 마이그레이션 `db/migration/<vendor>/V20261104100000__videos.sql` 을 앱의 `spring.flyway.locations=classpath:db/migration/{vendor}` 가 고른다.
 *
 * **이 클래스의 이름(`dev.sumin.skeleton.video.jdbc.VideoJdbcAutoConfiguration`)을 `video` 의 `VideoAutoConfiguration` 이 문자열로 가리킨다.**
 * 작업 큐(`job-queue-jdbc`)가 꺼져 있으면(`skeleton.job-queue.enabled=false`) [VideoFollowUps] 가 서지 않아 `video` 가 기동 실패로 말해 준다 - 뒷일 없이 변환을 약속할 수 없다.
 * SqlDialect 는 조건에 넣지 않는다 (방언 자동설정보다 먼저 평가된다 - 존재는 SqlDialectVerifier 가 보장).
 */
@AutoConfiguration(after = [DataSourceAutoConfiguration::class, DataSourceTransactionManagerAutoConfiguration::class, JobQueueJdbcAutoConfiguration::class])
@ConditionalOnClass(NamedParameterJdbcTemplate::class)
@ConditionalOnBean(DataSource::class)
class VideoJdbcAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(VideoStore::class)
    fun jdbcVideoStore(dataSource: DataSource, dialect: SqlDialect, transactionManager: PlatformTransactionManager): VideoStore =
        JdbcVideoStore(NamedParameterJdbcTemplate(dataSource), dialect, TransactionTemplate(transactionManager))

    @Bean
    @ConditionalOnMissingBean(VideoWorkerStore::class)
    fun jdbcVideoWorkerStore(dataSource: DataSource, dialect: SqlDialect): VideoWorkerStore = JdbcVideoWorkerStore(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(VideoWakeRuns::class)
    fun jdbcVideoWakeRuns(dataSource: DataSource, dialect: SqlDialect): VideoWakeRuns = JdbcVideoWakeRuns(NamedParameterJdbcTemplate(dataSource), dialect)

    @Bean
    @ConditionalOnMissingBean(DisplayJobStore::class)
    fun jdbcDisplayJobStore(dataSource: DataSource, dialect: SqlDialect, transactionManager: PlatformTransactionManager): DisplayJobStore =
        JdbcDisplayJobStore(NamedParameterJdbcTemplate(dataSource), dialect, TransactionTemplate(transactionManager))

    @Bean
    @ConditionalOnMissingBean(VideoTransactions::class)
    fun jdbcVideoTransactions(transactionManager: PlatformTransactionManager): VideoTransactions = JdbcVideoTransactions(TransactionTemplate(transactionManager))

    @Bean
    @ConditionalOnBean(JobQueue::class)
    @ConditionalOnMissingBean(VideoFollowUps::class)
    fun jobQueueVideoFollowUps(queue: JobQueue, time: TimeProvider): VideoFollowUps = JobQueueVideoFollowUps(queue, time)

    /** 큐 워커가 `JobHandler` 빈을 워커가 만들어질 때 모은다 - 처리기는 서비스를 늦게 찾는다(빈 순환을 피한다) */
    @Bean
    @ConditionalOnBean(JobQueue::class)
    @ConditionalOnMissingBean(VideoFinalizeJobHandler::class)
    fun videoFinalizeJobHandler(finalizer: ObjectProvider<VideoFinalizer>): VideoFinalizeJobHandler = VideoFinalizeJobHandler { finalizer.getObject() }

    @Bean
    @ConditionalOnBean(JobQueue::class)
    @ConditionalOnMissingBean(VideoWakeJobHandler::class)
    fun videoWakeJobHandler(runner: ObjectProvider<VideoWakeRunner>): VideoWakeJobHandler = VideoWakeJobHandler { runner.getObject() }
}
