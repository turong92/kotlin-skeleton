package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import java.time.Instant

internal object BroadcastIds {
    /** 공지 id = 알림 이벤트 id — 전달 고리가 이 접두사로 자기 것을 안다 */
    const val PREFIX = "bc_"

    /** 테스트 발송 — 접두사가 `bc_` 와 달라 통계에 안 센다 */
    const val TEST_PREFIX = "bct_"
}

/** 공지 줄 → 알림 이벤트. 본문 길이 한도는 알림함 칸(제목 255 · 본문 2000자)과 같다 */
internal class BroadcastEvents(private val gate: AdvertisingGate, private val topic: String) {
    fun event(
        broadcast: Broadcast,
        eventId: String,
        recipients: Set<String>,
        titlePrefix: String,
        at: Instant,
    ): NotificationEvent {
        val content = broadcast.content
        val (title, body) = branded(content.type, titlePrefix + content.title, content.body)
        return NotificationEvent(
            topic = topic,
            type = content.type,
            id = eventId,
            recipientIds = recipients,
            severity = NotificationSeverity.INFO,
            title = title,
            message = body,
            payload = mapOf("broadcastId" to broadcast.id),
            createdAt = at,
            link = content.link,
            channels = content.channels.toSet(),
        )
    }

    /** 광고 종류면 알림함에도 `(광고)` 표기(멱등이라 푸시에서 두 번 붙지 않는다). 붙인 본문이 알림함 칸(2000자)을 넘으면 본문을 줄여 표기를 지킨다 */
    private fun branded(type: String, title: String, body: String?): Pair<String, String?> {
        if (!gate.isAdvertising(type)) return title to body
        var branded = gate.brand(title, body, MAX_BRAND_BYTES)
        val over = (branded.body?.length ?: 0) - MAX_BODY_CHARS
        if (over > 0) branded = gate.brand(title, body, MAX_BRAND_BYTES - over * 4)   // 한 글자는 최대 4바이트 - 한 번에 충분히 줄어든다
        return branded.title to branded.body
    }

    private companion object {
        const val MAX_BODY_CHARS = 2000
        const val MAX_BRAND_BYTES = 8000
    }
}
