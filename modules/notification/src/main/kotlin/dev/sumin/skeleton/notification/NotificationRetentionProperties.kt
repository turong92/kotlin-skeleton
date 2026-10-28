package dev.sumin.skeleton.notification

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 받은편지함 보관 정리 - 기본은 꺼짐: 지우는 것은 보관 정책이라 앱이 정한다.
 * ```yaml
 * skeleton:
 *   notification:
 *     retention:
 *       enabled: false   # true 면 interval 마다 max-age 가 지난 줄을 지운다 (scheduler 모듈의 skeletonTaskScheduler 가 있어야 돈다)
 *       max-age: 90d
 *       interval: 1h
 * ```
 */
@ConfigurationProperties("skeleton.notification.retention")
data class NotificationRetentionProperties(
    val enabled: Boolean = false,
    /** 알림 시각(`event_created_at`)이 이 기간보다 오래된 줄을 읽음 여부와 무관하게 지운다 */
    val maxAge: Duration = Duration.ofDays(90),
    /** 정리를 도는 간격 */
    val interval: Duration = Duration.ofHours(1),
) {
    init {
        require(!maxAge.isNegative && !maxAge.isZero) { "skeleton.notification.retention.max-age must be positive" }
        require(!interval.isNegative && !interval.isZero) { "skeleton.notification.retention.interval must be positive" }
    }
}
