package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.notification.NotificationEvent
import java.time.Instant

/**
 * 한 사람에게 [runAt] 에 보낼 푸시 한 통의 예약. [key] 가 같으면 같은 예약이다(다시 예약하면 아직 안 나간 것을 바꾼다) - 앱이 정한다, 예: `resume:{userId}:{seriesId}`.
 * [advertising] 이 true 면 광고성(동의 없으면 안 보냄 · 야간은 다음 아침으로 · `(광고)` 표기). 내용은 로그에 싣지 않는다.
 */
data class ScheduledPush(
    val key: String,
    val recipientId: String,
    val runAt: Instant,
    val title: String,
    val body: String? = null,
    val link: String? = null,
    val advertising: Boolean = false,
) {
    init {
        require(key.isNotBlank() && key.length <= KEY_MAX) { "key must be 1..$KEY_MAX characters" }
        require(recipientId.isNotBlank() && recipientId.length <= RECIPIENT_MAX) { "recipientId must be 1..$RECIPIENT_MAX characters" }
        require(title.isNotBlank() && title.length <= TITLE_MAX) { "title must be 1..$TITLE_MAX characters" }
        require(body == null || body.length <= BODY_MAX) { "body must be at most $BODY_MAX characters" }
        require(link == null || NotificationEvent.isValidLink(link)) { "link must be an in-app path (a single leading '/')" }
    }

    override fun toString() = "ScheduledPush(key=$key, recipientId=$recipientId, runAt=$runAt, advertising=$advertising, title=<redacted>)"

    companion object {
        const val KEY_MAX = 200
        const val RECIPIENT_MAX = 128
        const val TITLE_MAX = 100
        const val BODY_MAX = 2000
    }
}

enum class ScheduleResult {
    /** 새로 예약했다 */
    CREATED,

    /** 아직 안 나간 같은 키의 예약을 바꿨다 */
    REPLACED,

    /** 같은 키가 이미 나갔거나 · 건너뛰었거나 · 지금 나가는 중이라 그대로 뒀다 - 같은 계기는 한 통만 간다 */
    IGNORED,
}

/** 예약이 끝난 모양 */
enum class ScheduledPushStatus { PENDING, SENT, SKIPPED }

enum class SkipReason {
    /** 광고성인데 수신 동의가 없다 */
    NO_CONSENT,

    /** 그 사람의 하루 상한에 닿았다 */
    DAILY_LIMIT,

    /** 보내려다 [ScheduledPushProperties.maxAttempts] 번 실패했다 */
    TOO_MANY_ATTEMPTS,
}

/**
 * 훑기가 집은 예약 한 건. [claimToken] 은 이 집기의 임대 만료 시각이다 - [ScheduledPushStore.finish] · [ScheduledPushStore.reschedule] 은 이 값이 줄과 같을 때만 듣는다
 * (임대가 끝나 다른 훑기가 다시 집었거나 앱이 예약을 바꿨다면 옛 훑기의 닫기는 아무것도 못 한다).
 */
data class DuePush(val push: ScheduledPush, val attempts: Int, val claimToken: Instant)

/** 예약된 개인 푸시 수 · 보낸 수 · 건너뛴 수 */
data class ScheduledPushStats(val pending: Long, val sent: Long, val skipped: Long)
