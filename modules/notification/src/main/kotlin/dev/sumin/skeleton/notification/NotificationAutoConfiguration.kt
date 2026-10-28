package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.TaskScheduler

@AutoConfiguration
@EnableConfigurationProperties(NotificationRetentionProperties::class)
class NotificationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(NotificationRecipientResolver::class)
    fun notificationRecipientResolver(): NotificationRecipientResolver =
        DefaultNotificationRecipientResolver()

    @Bean
    @ConditionalOnMissingBean(NotificationInboxRepository::class)
    fun notificationInboxRepository(): NotificationInboxRepository =
        InMemoryNotificationInboxRepository()

    @Bean
    @ConditionalOnMissingBean(NotificationBroker::class)
    fun notificationBroker(
        notificationInboxRepository: NotificationInboxRepository,
        notificationRecipientResolver: NotificationRecipientResolver,
    ): NotificationBroker =
        InMemoryNotificationBroker(notificationInboxRepository, notificationRecipientResolver)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.notification.retention", name = ["enabled"], havingValue = "true")
    fun notificationInboxRetention(
        notificationInboxRepository: NotificationInboxRepository,
        properties: NotificationRetentionProperties,
        timeProvider: ObjectProvider<TimeProvider>,
    ): NotificationInboxRetention =
        NotificationInboxRetention(notificationInboxRepository, properties, timeProvider.getIfAvailable { TimeProvider.systemUtc() })

    /**
     * `interval` 마다 보관 정리를 돈다. `scheduler` 모듈의 `skeletonTaskScheduler` 가 없으면 경고만 남기고 돌지 않는다(앱이 [NotificationInboxRetention.run] 을 직접 부를 수 있다).
     * 프로젝트 모드면 서비스 중인 프로젝트마다 그 프로젝트 문맥 안에서 지운다. 정리가 실패해도 다음 주기에 다시 돈다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.notification.retention", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(name = ["notificationInboxRetentionSchedule"])
    fun notificationInboxRetentionSchedule(
        retention: NotificationInboxRetention,
        properties: NotificationRetentionProperties,
        @Qualifier("skeletonTaskScheduler") schedulers: ObjectProvider<TaskScheduler>,
        projects: ObjectProvider<ProjectRegistry>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val log = LoggerFactory.getLogger(NotificationInboxRetention::class.java)
        val scheduler = schedulers.getIfAvailable()
        if (scheduler == null) {
            log.warn("notification retention is on but there is no skeletonTaskScheduler bean (add the scheduler module) - old inbox rows are only removed when the app calls NotificationInboxRetention.run")
        } else {
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        val registry = projects.getIfAvailable()
                        if (registry == null) {
                            retention.run()
                        } else {
                            registry.forEachProject { project ->
                                try {
                                    retention.run()
                                } catch (e: Exception) {
                                    log.warn("notification retention failed for project '{}': {}", project.id, e.toString())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("notification retention failed: {}", e.toString())
                    }
                },
                Instant.now().plus(properties.interval),
                properties.interval,
            )
        }
    }
}
