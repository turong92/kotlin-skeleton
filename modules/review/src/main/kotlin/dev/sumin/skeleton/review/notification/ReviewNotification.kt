package dev.sumin.skeleton.review.notification

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.review.Review
import dev.sumin.skeleton.review.ReviewAutoConfiguration
import dev.sumin.skeleton.review.ReviewNotifier
import dev.sumin.skeleton.review.ReviewProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import java.util.Locale

/**
 * 숨김 · 답글을 작성자 받은편지함으로 - 알림 id 는 (종류, 리뷰 id, 시각)에서 만들어 같은 일을 두 번 알리지 않는다. 문구는 서버 기본 로케일(한국어 · 영어).
 * 알림 payload 에는 리뷰 id · 대상 · 사유만 싣는다(계정 id 는 받는 사람 칸에만 있다).
 */
class NotificationReviewNotifier(
    private val publisher: NotificationPublisher,
    private val topic: String,
    private val locale: Locale = Locale.getDefault(),
) : ReviewNotifier {
    private val korean get() = locale.language == "ko"

    override fun hidden(accountId: String, review: Review, reason: String) {
        publisher.publish(
            NotificationEvent(
                topic = topic,
                type = "review.hidden",
                id = "rvh_${review.id}_${review.updatedAt.toEpochMilli()}",
                recipientIds = setOf(accountId),
                severity = NotificationSeverity.WARNING,
                title = if (korean) "리뷰가 숨겨졌어요" else "Your review was hidden",
                message = reason,
                payload = mapOf("reviewId" to review.id, "targetType" to review.targetType, "targetId" to review.targetId, "reason" to reason),
            ),
        )
    }

    override fun replied(accountId: String, review: Review) {
        publisher.publish(
            NotificationEvent(
                topic = topic,
                type = "review.replied",
                recipientIds = setOf(accountId),
                severity = NotificationSeverity.INFO,
                title = if (korean) "리뷰에 답글이 달렸어요" else "Your review got a reply",
                message = if (korean) "운영자가 답글을 남겼어요" else "A moderator replied to your review",
                payload = mapOf("reviewId" to review.id, "targetType" to review.targetType, "targetId" to review.targetId),
            ),
        )
    }
}

/**
 * `notification` 모듈이 클래스패스에 있고 [NotificationPublisher] 빈이 있을 때만 - [ReviewAutoConfiguration] 보다 먼저 등록해 그쪽의 기본(알림 없음)을 물린다.
 * `skeleton.review.notify.enabled=false` 로 끈다.
 */
@AutoConfiguration(
    before = [ReviewAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.NotificationAutoConfiguration"],
)
@ConditionalOnClass(NotificationPublisher::class)
@ConditionalOnProperty(prefix = "skeleton.review.notify", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReviewNotificationAutoConfiguration {
    @Bean
    @ConditionalOnBean(NotificationPublisher::class)
    @ConditionalOnMissingBean(ReviewNotifier::class)
    fun reviewNotifier(publisher: NotificationPublisher, properties: ReviewProperties): ReviewNotifier =
        NotificationReviewNotifier(publisher, properties.notify.topic)
}
