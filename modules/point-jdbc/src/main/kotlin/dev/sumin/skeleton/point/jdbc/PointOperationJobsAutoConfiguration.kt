package dev.sumin.skeleton.point.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.point.PointOperationScheduler
import dev.sumin.skeleton.point.PointOperations
import dev.sumin.skeleton.point.PointProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * 불명 건 조회 작업 - `job-queue-jdbc` 가 클래스패스에 있고 `JobQueue` 빈이 있을 때만 선다(없으면 조회는 앱이 [PointOperations.resolve] 를 부른다).
 * [PointJdbcAutoConfiguration] 과 형제 설정이다(중첩의 중첩 클래스는 스캔되지 않는다) - 예약기 빈은 거기서 지연해서 찾는다.
 */
@AutoConfiguration(
    afterName = ["dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration"],
    before = [PointJdbcAutoConfiguration::class],
)
@ConditionalOnClass(JobQueue::class)
@ConditionalOnBean(JobQueue::class)
class PointOperationJobsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(PointOperationScheduler::class)
    fun pointOperationScheduler(queue: JobQueue, properties: PointProperties): PointOperationScheduler =
        JobQueuePointOperationScheduler(queue, properties.operations.reconcile.maxAttempts)

    @Bean
    @ConditionalOnMissingBean(name = ["pointOperationResolveHandler"])
    fun pointOperationResolveHandler(operations: ObjectProvider<PointOperations>): PointOperationResolveHandler =
        PointOperationResolveHandler { operations.getObject() }

    @Bean
    @ConditionalOnMissingBean(name = ["pointOperationDeadListener"])
    fun pointOperationDeadListener(operations: ObjectProvider<PointOperations>): PointOperationDeadListener =
        PointOperationDeadListener { operations.getObject() }
}
