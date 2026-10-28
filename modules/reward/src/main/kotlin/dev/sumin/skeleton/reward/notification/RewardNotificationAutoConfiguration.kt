package dev.sumin.skeleton.reward.notification

import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.reward.RewardAutoConfiguration
import dev.sumin.skeleton.reward.RewardNotifier
import dev.sumin.skeleton.reward.RewardProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * 받았어요 알림을 받은편지함으로 — 알림 id 는 지급 건에서 만든다(`rwd_<건 id>`)라 받은편지함의 `(받는 사람, 이벤트 id)` 가 중복을 접는다.
 * 문구는 지급기의 `describe`, 링크는 payload 에 실어 둔다(지금은 눌리지 않는다).
 */
class NotificationRewardNotifier(private val publisher: NotificationPublisher, private val topic: String) : RewardNotifier {
    override fun issued(grant: RewardGrantView, label: RewardLabel) {
        publisher.publish(
            NotificationEvent(
                topic = topic,
                type = "reward.granted",
                id = "rwd_${grant.id}",
                recipientIds = setOf(grant.accountId),
                severity = NotificationSeverity.SUCCESS,
                title = label.title,
                message = label.message,
                payload = mapOf("rewardType" to grant.type, "label" to label.title, "link" to label.link),
            ),
        )
    }
}

/**
 * `notification` 모듈이 클래스패스에 있고 [NotificationPublisher] 빈이 있을 때만 — [RewardAutoConfiguration] 보다 먼저 등록해 그쪽의 기본(알림 없음)을 물린다.
 * `skeleton.reward.notify.enabled=false` 로 끈다.
 */
@AutoConfiguration(
    before = [RewardAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.NotificationAutoConfiguration"],
)
@ConditionalOnClass(NotificationPublisher::class)
@ConditionalOnProperty(prefix = "skeleton.reward.notify", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class RewardNotificationAutoConfiguration {
    @Bean
    @ConditionalOnBean(NotificationPublisher::class)
    @ConditionalOnMissingBean(RewardNotifier::class)
    fun rewardNotifier(publisher: NotificationPublisher, properties: RewardProperties): RewardNotifier =
        NotificationRewardNotifier(publisher, properties.notify.topic)
}
