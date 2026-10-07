package dev.sumin.skeleton.board.notification

import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardCommentNotice
import dev.sumin.skeleton.board.BoardNotifier
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/** 댓글 알림 문구 — 앱이 같은 타입의 빈을 만들면 기본 영문 문구가 물러난다 */
data class BoardNotificationMessage(val title: String, val message: String)

fun interface BoardNotificationFormatter {
    fun format(notice: BoardCommentNotice): BoardNotificationMessage
}

class DefaultBoardNotificationFormatter : BoardNotificationFormatter {
    override fun format(notice: BoardCommentNotice): BoardNotificationMessage {
        val title = if (notice.parent == null) "New comment on your post: ${shorten(notice.post.title, 60)}" else "New reply to your comment"
        return BoardNotificationMessage(title, shorten(notice.comment.body, 140))
    }

    private fun shorten(text: String, max: Int): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.substring(0, max - 1) + "…"
    }
}

/** 댓글 · 답글을 `notification` 모듈의 [NotificationPublisher] 로 보낸다 (받는 사람 = [BoardCommentNotice.recipientId]). */
class NotificationBoardNotifier(
    private val publisher: NotificationPublisher,
    private val formatter: BoardNotificationFormatter,
    private val topic: String,
) : BoardNotifier {
    override fun commentCreated(notice: BoardCommentNotice) {
        val text = formatter.format(notice)
        publisher.publish(
            NotificationEvent(
                topic = topic,
                type = if (notice.parent == null) "comment.created" else "comment.replied",
                recipientIds = setOf(notice.recipientId),
                title = text.title,
                message = text.message,
                payload = mapOf(
                    "boardCode" to notice.post.boardCode,
                    "postId" to notice.post.id,
                    "commentId" to notice.comment.id,
                    "parentId" to notice.parent?.id,
                    "authorId" to notice.comment.authorId,
                    "authorName" to notice.authorName,
                ),
            ),
        )
    }
}

/**
 * `notification` 모듈이 클래스패스에 있고 [NotificationPublisher] 빈이 있을 때만 — [BoardAutoConfiguration] 보다 먼저 등록해 그쪽의 기본(알림 없음)을 물린다.
 * `skeleton.board.notification.enabled=false` 로 끈다.
 */
@AutoConfiguration(
    before = [BoardAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.notification.NotificationAutoConfiguration"],
)
@ConditionalOnClass(NotificationPublisher::class)
@ConditionalOnProperty(prefix = "skeleton.board.notification", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BoardNotificationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun boardNotificationFormatter(): BoardNotificationFormatter = DefaultBoardNotificationFormatter()

    @Bean
    @ConditionalOnBean(NotificationPublisher::class)
    @ConditionalOnMissingBean(BoardNotifier::class)
    fun boardNotifier(publisher: NotificationPublisher, formatter: BoardNotificationFormatter, properties: BoardProperties): BoardNotifier =
        NotificationBoardNotifier(publisher, formatter, properties.notification.topic)
}
