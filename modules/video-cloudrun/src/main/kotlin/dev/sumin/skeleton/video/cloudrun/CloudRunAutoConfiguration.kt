package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.alert.VideoAlertKind
import dev.sumin.skeleton.video.VideoAutoConfiguration
import dev.sumin.skeleton.video.VideoWakeRuns
import dev.sumin.skeleton.video.VideoWaker
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 기본 꺼짐 - `skeleton.video.cloud-run.enabled=true` 로 켜면 `video` 의 기본 [dev.sumin.skeleton.video.NoopWaker] 대신 상한을 두른 Cloud Run 깨우기가 선다.
 * 켰는데 서비스 계정 키가 없거나 읽을 수 없으면 기동이 실패한다(값은 싣지 않고 속성 이름만). 깨우기 기록 표는 `video-jdbc` 가 낸다
 */
@AutoConfiguration(before = [VideoAutoConfiguration::class], afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@EnableConfigurationProperties(CloudRunProperties::class)
@ConditionalOnProperty(prefix = "skeleton.video.cloud-run", name = ["enabled"], havingValue = "true")
class CloudRunAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(VideoWaker::class)
    fun videoWaker(
        props: CloudRunProperties,
        runs: ObjectProvider<VideoWakeRuns>,
        alert: ObjectProvider<WakeFailureAlert>,
        time: ObjectProvider<TimeProvider>,
    ): VideoWaker {
        val clock = time.getIfAvailable { TimeProvider.systemUtc() }
        val log = org.slf4j.LoggerFactory.getLogger(CloudRunAutoConfiguration::class.java)
        return GuardedWaker(
            CloudRunWaker(props, clock),
            runs.getIfAvailable() ?: error("skeleton.video.cloud-run.enabled=true needs a VideoWakeRuns bean (add modules:video-jdbc)"),
            props,
            alert.getIfAvailable { WakeFailureAlert { n, why -> log.warn("video wake failed {} time(s) in a row and no alert module is present: {}", n, why) } },
            clock,
        )
    }

    /** `alert` 모듈이 있으면 연속 실패를 주인 경보로 낸다 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OwnerAlerts::class)
    class WakeAlertConfiguration {
        @Bean
        @ConditionalOnBean(OwnerAlerts::class)
        @ConditionalOnMissingBean(WakeFailureAlert::class)
        fun cloudRunWakeFailureAlert(alerts: OwnerAlerts): WakeFailureAlert = WakeFailureAlert { failures, reason ->
            alerts.emit(
                VideoAlertKind.VIDEO_WAKE_FAILED, "cloud-run",
                "Waking the Cloud Run job failed $failures times in a row ($reason). Compatible and ladder work is waiting in the queue - check the credentials and settings",
                immediate = true,
            )
        }
    }
}
