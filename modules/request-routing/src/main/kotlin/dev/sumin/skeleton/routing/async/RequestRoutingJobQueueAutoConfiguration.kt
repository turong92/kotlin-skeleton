package dev.sumin.skeleton.routing.async

import dev.sumin.skeleton.jobqueue.jdbc.JobLanes
import dev.sumin.skeleton.routing.RequestRoutingProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/** 작업 큐(`job-queue-jdbc`)가 클래스패스에 있을 때만 — 큐의 기본 `JobLanes.NONE` 보다 먼저 서야 한다 */
@AutoConfiguration(beforeName = ["dev.sumin.skeleton.jobqueue.jdbc.JobQueueJdbcAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.jobqueue.jdbc.JobLanes"])
@ConditionalOnProperty("skeleton.routing.enabled", havingValue = "true")
@EnableConfigurationProperties(RequestRoutingProperties::class)
class RequestRoutingJobQueueAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(JobLanes::class)
    fun routingJobLanes(properties: RequestRoutingProperties): JobLanes = RoutingJobLanes(properties)
}
