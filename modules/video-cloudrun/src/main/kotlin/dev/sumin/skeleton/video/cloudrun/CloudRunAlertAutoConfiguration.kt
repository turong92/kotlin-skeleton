package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.video.VideoAutoConfiguration
import dev.sumin.skeleton.video.alert.VideoAlertKind
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/** `alert` 모듈이 있으면 Cloud Run 깨우기의 연속 실패를 주인 경보로 낸다([CloudRunAutoConfiguration] 의 깨우기가 이 빈을 쓴다) */
@AutoConfiguration(before = [VideoAutoConfiguration::class], afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@ConditionalOnProperty(prefix = "skeleton.video.cloud-run", name = ["enabled"], havingValue = "true")
@ConditionalOnClass(OwnerAlerts::class)
class CloudRunAlertAutoConfiguration {
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
