package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.ConsentService
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.LegalAutoConfiguration
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import dev.sumin.skeleton.notification.legal.AdvertisingTypesConfigured
import dev.sumin.skeleton.notification.legal.NotificationLegalAutoConfiguration
import dev.sumin.skeleton.notification.push.PushAdvertisingNoticeStore
import dev.sumin.skeleton.notification.push.PushAutoConfiguration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.scheduling.TaskScheduler

/**
 * 광고 동의 어댑터 - 푸시가 켜져 있고(`enabled`) 광고 종류가 설정됐고 `legal` 의 [ConsentService] 가 있을 때만 선다(광고를 안 켜면 아무 빈도 없다 = 이 모듈 없는 앱과 같다).
 * 동의 변경 통지([ConsentChangeListener]) · 재확인 스캔(`reconfirm-scan-interval` 마다, 프로젝트마다, `skeletonTaskScheduler`).
 * 정책 빈(`AdvertisingConsentPolicy`)은 채널 무관 어댑터 `notification-legal` 이 낸다.
 */
@AutoConfiguration(after = [PushAutoConfiguration::class, LegalAutoConfiguration::class, NotificationLegalAutoConfiguration::class])
@ConditionalOnProperty(prefix = "skeleton.notification.push", name = ["enabled"], havingValue = "true")
@Conditional(AdvertisingTypesConfigured::class)
@ConditionalOnBean(ConsentService::class)
class PushLegalAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun advertisingConsentNotifier(publisher: NotificationPublisher, properties: AdvertisingProperties, gate: AdvertisingGate): AdvertisingConsentNotifier =
        AdvertisingConsentNotifier(publisher, properties, gate)

    @Bean
    @ConditionalOnMissingBean
    fun advertisingReconfirmScanner(
        store: ConsentStore,
        notices: PushAdvertisingNoticeStore,
        publisher: NotificationPublisher,
        gate: AdvertisingGate,
        properties: AdvertisingProperties,
        time: ObjectProvider<TimeProvider>,
    ): AdvertisingReconfirmScanner =
        AdvertisingReconfirmScanner(store, notices, publisher, gate, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    /**
     * `reconfirm-scan-interval` 마다 재확인 안내를 스캔한다. `scheduler` 모듈의 `skeletonTaskScheduler` 가 없으면 경고만 남기고 돌지 않는다(앱이 [AdvertisingReconfirmScanner.run] 을 직접 부를 수 있다).
     * 프로젝트 모드면 서비스 중인 프로젝트마다 그 문맥 안에서 돈다. 실패해도 다음 주기에 다시 돈다.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["advertisingReconfirmSchedule"])
    fun advertisingReconfirmSchedule(
        scanner: AdvertisingReconfirmScanner,
        properties: AdvertisingProperties,
        @Qualifier("skeletonTaskScheduler") schedulers: ObjectProvider<TaskScheduler>,
        projects: ObjectProvider<ProjectRegistry>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val log = LoggerFactory.getLogger(AdvertisingReconfirmScanner::class.java)
        val scheduler = schedulers.getIfAvailable()
        if (scheduler == null) {
            log.warn("advertising reconfirm scan has no skeletonTaskScheduler bean (add the scheduler module) - reminders are only sent when the app calls AdvertisingReconfirmScanner.run")
        } else {
            val interval = properties.reconfirmScanInterval
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        val registry = projects.getIfAvailable()
                        if (registry == null) {
                            scanner.run()
                        } else {
                            registry.forEachProject { project ->
                                try {
                                    scanner.run()
                                } catch (e: Exception) {
                                    log.warn("advertising reconfirm scan failed for project '{}': {}", project.id, e.toString())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("advertising reconfirm scan failed: {}", e.toString())
                    }
                },
                Instant.now().plus(interval),
                interval,
            )
        }
    }
}
