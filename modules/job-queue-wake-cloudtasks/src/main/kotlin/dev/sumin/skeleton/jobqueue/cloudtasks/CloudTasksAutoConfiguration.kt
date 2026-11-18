package dev.sumin.skeleton.jobqueue.cloudtasks

import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * `JobWake` 를 Cloud Tasks 로 낸다. 작업 큐(`job-queue-jdbc`)가 있을 때 서고, 빈은 **늘 선다** — 켜졌는지(project · region · queue · target-url 이 다 찼나)는 요청마다 읽는다.
 * `@ConditionalOnProperty` 로 걸면 네이티브 AOT 이미지에서 빌드 때 굳어, 실행 환경변수로만 켠 배포에서 깨우기가 사라진다. 비어 있으면 `request` 는 아무것도 하지 않는다.
 */
@AutoConfiguration(after = [JobQueueJdbcAutoConfiguration::class])
@ConditionalOnBean(JobQueueProperties::class)
@EnableConfigurationProperties(CloudTasksProperties::class)
class CloudTasksAutoConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnMissingBean(JobWake::class)
    fun cloudTasksJobWake(props: CloudTasksProperties, queue: JobQueueProperties, time: ObjectProvider<TimeProvider>): JobWake {
        val clock = time.getIfAvailable { TimeProvider.systemUtc() }
        if (props.configured) {
            log.info("Job wake: Cloud Tasks queue {} ({}, {})", props.queue, props.project, props.region)
        } else {
            log.info("Job wake: Cloud Tasks is not configured (empty: {}); wake requests do nothing", props.missing().joinToString(", "))
        }
        return CloudTasksJobWake(props, queue.wake, MetadataTokenSource(props.metadataUrl, props.timeout, clock), clock)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["cloudTasksDeployGuard"])
    fun cloudTasksDeployGuard(props: CloudTasksProperties, queue: JobQueueProperties): DeployGuard = CloudTasksDeployGuard(props, queue)
}
