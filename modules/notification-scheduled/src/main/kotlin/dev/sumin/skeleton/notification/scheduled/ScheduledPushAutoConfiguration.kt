package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.PeriodicRunCheck
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * 사람마다 따로 예약하는 푸시. **모듈 기본은 꺼짐** - `skeleton.notification.scheduled.enabled=true` 일 때만 선다. 저장소([ScheduledPushStore])는 `notification-scheduled-jdbc` 가 낸다.
 * 켰는데 저장소 · 알림 종류가 없거나 종류가 광고 설정과 맞지 않으면 **시작을 멈추고** 무엇을 하면 되는지 말한다.
 */
@AutoConfiguration(
    after = [NotificationAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.scheduled.jdbc.ScheduledPushJdbcAutoConfiguration"],
)
@EnableConfigurationProperties(ScheduledPushProperties::class)
@ConditionalOnProperty(prefix = "skeleton.notification.scheduled", name = ["enabled"], havingValue = "true")
class ScheduledPushAutoConfiguration {
    @Bean
    fun scheduledPushSetupCheck(properties: ScheduledPushProperties, store: ObjectProvider<ScheduledPushStore>, gate: AdvertisingGate, environment: Environment): ScheduledPushSetupCheck {
        // 훑기를 돌릴 길이 없으면(scheduler 도 요청 구동 모드도 없다) 예약이 한 통도 안 나가는데 아무 소리도 없다
        PeriodicRunCheck.require("skeleton.notification.scheduled.enabled", environment)
        check(store.getIfAvailable() != null) {
            "skeleton.notification.scheduled.enabled=true needs a ScheduledPushStore - add the notification-scheduled-jdbc module " +
                "(implementation(project(\":modules:notification-scheduled-jdbc\"))) and its migration"
        }
        check(properties.type.isNotBlank()) { "skeleton.notification.scheduled.type must name the notification type of the informational pushes (also list it in skeleton.notification.push.types)" }
        check(!gate.isAdvertising(properties.type)) {
            "skeleton.notification.scheduled.type '${properties.type}' is listed in skeleton.notification.advertising.types - use advertising-type for advertising pushes"
        }
        if (properties.advertisingType.isNotBlank()) {
            check(gate.isAdvertising(properties.advertisingType)) {
                "skeleton.notification.scheduled.advertising-type '${properties.advertisingType}' must be listed in skeleton.notification.advertising.types (the consent gate applies to it)"
            }
        }
        return ScheduledPushSetupCheck
    }

    @Bean
    @ConditionalOnMissingBean
    fun scheduledPushes(@Suppress("UNUSED_PARAMETER") setup: ScheduledPushSetupCheck, store: ScheduledPushStore, properties: ScheduledPushProperties, time: ObjectProvider<TimeProvider>, wake: ObjectProvider<JobWake>): ScheduledPushes =
        ScheduledPushes(store, properties, time.getIfAvailable { TimeProvider.systemUtc() }, wake.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    fun scheduledPushSweep(
        @Suppress("UNUSED_PARAMETER") setup: ScheduledPushSetupCheck,
        store: ScheduledPushStore,
        publisher: NotificationPublisher,
        gate: AdvertisingGate,
        properties: ScheduledPushProperties,
        time: ObjectProvider<TimeProvider>,
    ): ScheduledPushSweep = ScheduledPushSweep(store, publisher, gate, properties, time.getIfAvailable { TimeProvider.systemUtc() })

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 - 집기가 조건부 갱신이라 타이머와 겹쳐 돌아도 한 번만 나간다 */
    @Bean
    @ConditionalOnMissingBean(name = ["scheduledPushSweepTask"])
    fun scheduledPushSweepTask(sweep: ScheduledPushSweep): OnRequestTask = SimpleOnRequestTask("notification-scheduled-sweep") {
        sweep.run()
        sweep.purge()
    }

    @Bean
    @ConditionalOnMissingBean(name = ["scheduledPushErasureListener"])
    fun scheduledPushErasureListener(@Suppress("UNUSED_PARAMETER") setup: ScheduledPushSetupCheck, store: ScheduledPushStore): AccountErasureListener =
        ScheduledPushErasureListener(store)
}

/** 설정 검사가 끝났다는 표지 - 이 빈을 받는 빈은 검사를 먼저 거친다 */
object ScheduledPushSetupCheck
