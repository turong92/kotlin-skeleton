package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity

/**
 * 기본 factory — `lock-screen` 설정을 따른다.
 * FULL: 제목 `title ?: type` · 본문 `message` · `link` · 묶기 열쇠. HIDDEN: 제목 = `hidden-title` · 본문 = `hidden-body`(비면 없음) · `link` 는 그대로 · 묶기 열쇠는 해시 -
 * 실제 `title` · `message` 는 이 객체에 들어오지 않으므로 작업 내용에도 푸시 서비스에도 가지 않는다.
 * 설정은 이 기본 구현만 따른다 - 앱이 [PushMessageFactory] 빈을 바꾸면 가리기도 그 앱의 책임이다.
 */
class DefaultPushMessageFactory(private val properties: PushProperties) : PushMessageFactory {
    override fun create(event: NotificationEvent): PushMessage {
        val urgency = if (event.severity == NotificationSeverity.ERROR || event.severity == NotificationSeverity.WARNING) PushUrgency.HIGH else PushUrgency.NORMAL
        return when (properties.lockScreen) {
            LockScreen.FULL -> PushMessage(
                eventId = event.id, type = event.type, title = event.title ?: event.type, body = event.message,
                link = event.link, tag = event.collapseKey, urgency = urgency, createdAt = event.createdAt,
            )
            LockScreen.HIDDEN -> PushMessage(
                eventId = event.id, type = event.type, title = properties.hiddenTitle, body = properties.hiddenBody.takeIf { it.isNotBlank() },
                link = event.link, tag = event.collapseKey?.let { PushHashes.sha256Hex(it).take(32) }, urgency = urgency, createdAt = event.createdAt,
            )
        }
    }
}
