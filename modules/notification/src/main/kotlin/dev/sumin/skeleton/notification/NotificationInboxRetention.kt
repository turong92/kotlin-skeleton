package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

/**
 * 보관 기한이 지난 받은편지함 줄 정리. 저장소 계약([NotificationInboxRepository.deleteOlderThan])만 쓰므로 인메모리에도 돈다.
 * 여러 인스턴스가 같이 돌아도 결과가 같다(멱등). 앱이 직접 부르려면 [run].
 * 켜는 설정: `skeleton.notification.retention.enabled=true` ([NotificationRetentionProperties]).
 */
class NotificationInboxRetention(
    private val repository: NotificationInboxRepository,
    private val properties: NotificationRetentionProperties,
    private val timeProvider: TimeProvider = TimeProvider.systemUtc(),
) {
    /** [now] 에서 `max-age` 를 뺀 시각보다 오래된 줄을 지운다. 지운 줄 수 */
    fun run(now: Instant): Int = repository.deleteOlderThan(now.minus(properties.maxAge))

    /** 지금 시각([TimeProvider])으로 한 번 */
    fun run(): Int = run(timeProvider.now())
}
