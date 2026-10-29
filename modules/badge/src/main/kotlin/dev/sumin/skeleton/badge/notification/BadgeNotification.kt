package dev.sumin.skeleton.badge.notification

import dev.sumin.skeleton.badge.BadgeAutoConfiguration
import dev.sumin.skeleton.badge.BadgeDefinition
import dev.sumin.skeleton.badge.BadgeNotifier
import dev.sumin.skeleton.badge.BadgeProperties
import dev.sumin.skeleton.badge.BadgeRewardIssuer
import dev.sumin.skeleton.badge.BadgeSource
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.util.Locale

/**
 * 배지를 받았다는 알림을 받은편지함으로 — 앱 수여 · 운영 수여만(지급기 경로는 `reward` 가 이미 알린다). 알림 id 는 내역 줄에서 만든다(`bdg_<내역 id>`)라
 * 받은편지함의 `(받는 사람, 이벤트 id)` 가 중복을 접는다. 문구는 한국어 · 영어 중 서버 기본 로케일로 정한다.
 */
class NotificationBadgeNotifier(
    private val publisher: NotificationPublisher,
    private val topic: String,
    private val locale: Locale = Locale.getDefault(),
) : BadgeNotifier {
    override fun awarded(accountId: String, badge: BadgeDefinition, source: BadgeSource, eventId: String) {
        val korean = locale.language == "ko"
        publisher.publish(
            NotificationEvent(
                topic = topic,
                type = "badge.awarded",
                id = "bdg_$eventId",
                recipientIds = setOf(accountId),
                severity = NotificationSeverity.SUCCESS,
                title = badge.name,
                message = if (korean) "배지를 받았어요" else "You earned a badge",
                payload = mapOf("badgeCode" to badge.code, "label" to badge.name, "link" to BadgeRewardIssuer.LINK),
                link = BadgeRewardIssuer.LINK,
            ),
        )
    }
}

/**
 * `notification` 모듈이 클래스패스에 있고 [NotificationPublisher] 빈이 있을 때만 - [BadgeAutoConfiguration] 보다 먼저 등록해 그쪽의 기본(알림 없음)을 물린다.
 * `skeleton.badge.notify.enabled=false` 로 끈다.
 */
@AutoConfiguration(
    before = [BadgeAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.NotificationAutoConfiguration"],
)
@ConditionalOnClass(NotificationPublisher::class)
@ConditionalOnProperty(prefix = "skeleton.badge.notify", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BadgeNotificationAutoConfiguration {
    @Bean
    @ConditionalOnBean(NotificationPublisher::class)
    @ConditionalOnMissingBean(BadgeNotifier::class)
    fun badgeNotifier(publisher: NotificationPublisher, properties: BadgeProperties): BadgeNotifier =
        NotificationBadgeNotifier(publisher, properties.notify.topic)
}
