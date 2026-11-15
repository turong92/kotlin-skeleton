package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingDecision
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * 시각이 된 예약을 집어 보낸다. 1분마다 도는 타이머(`scheduler` 모듈이 있을 때)와 요청 구동 모드의 `notification-scheduled-sweep` 작업이 같은 [run] 을 부른다.
 *
 * - **한 번만**: 집기([ScheduledPushStore.claimDue])가 조건부 갱신이라 여러 인스턴스 중 하나만 한 줄을 받는다. 알림 이벤트 id 가 키에서 정해져(`sp_<해시>`) 집은 쪽이 보낸 뒤 닫기 전에 죽어 다시 집혀도 알림함이 한 줄로 합친다.
 * - **광고성**은 기존 광고 게이트를 그대로 탄다: 동의가 없으면 보내지 않고(건너뜀), 야간 동의가 없는 야간이면 다음 아침으로 미루고, 제목에 `(광고)` 를 붙인다.
 * - **하루 상한**은 그 사람이 지난 24시간에 받은 수로 센다. 닿으면 그 예약을 건너뛴다.
 */
class ScheduledPushSweep(
    private val store: ScheduledPushStore,
    private val publisher: NotificationPublisher,
    private val gate: AdvertisingGate,
    private val properties: ScheduledPushProperties,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이번에 집어 처리한 예약 수(보냄 · 건너뜀 · 미룸 모두) */
    fun run(): Int {
        val now = time.now()
        val due = store.claimDue(now, properties.lease, properties.batchSize)
        due.forEach { handle(it) }
        return due.size
    }

    /** `scheduler` 모듈이 있을 때 1분마다 - 없으면 표시가 무시되고 요청 구동 모드의 작업이 부른다 */
    @SkeletonScheduled(id = "notification.scheduled.sweep", fixedDelay = 1, timeUnit = TimeUnit.MINUTES, lockKey = "notification.scheduled.sweep")
    fun scheduled() {
        run()
    }

    /** 끝난 줄 중 `keep-finished` 보다 오래된 것을 지운다 - 훑기마다가 아니라 한 시간에 한 번이면 충분하다 */
    fun purge(): Int = store.purgeFinishedBefore(time.now().minus(properties.keepFinished))

    @SkeletonScheduled(id = "notification.scheduled.purge", fixedDelay = 1, timeUnit = TimeUnit.HOURS, lockKey = "notification.scheduled.purge")
    fun purgeScheduled() {
        purge()
    }

    private fun handle(due: DuePush) {
        val push = due.push
        val token = due.claimToken
        try {
            val now = time.now()
            if (due.attempts > properties.maxAttempts) {
                return store.finish(push.key, token, ScheduledPushStatus.SKIPPED, SkipReason.TOO_MANY_ATTEMPTS, now)
            }
            if (push.advertising) {
                when (val decision = gate.decide(push.recipientId)) {
                    AdvertisingDecision.Skip -> return store.finish(push.key, token, ScheduledPushStatus.SKIPPED, SkipReason.NO_CONSENT, now)
                    is AdvertisingDecision.Hold -> return store.reschedule(push.key, token, decision.runAt, now)
                    AdvertisingDecision.SendNow -> Unit
                }
            }
            val limit = properties.dailyLimit
            if (limit != null && store.sentCountSince(push.recipientId, now.minus(DAY)) >= limit) {
                return store.finish(push.key, token, ScheduledPushStatus.SKIPPED, SkipReason.DAILY_LIMIT, now)
            }
            publisher.publish(event(push, now))
            store.finish(push.key, token, ScheduledPushStatus.SENT, null, now)
        } catch (e: Exception) {
            // 집은 줄은 lease 가 지나면 다시 집힌다. 내용 · 받는 사람은 싣지 않는다
            log.warn("scheduled push failed, it will be retried after the lease: {}", e.javaClass.simpleName)
        }
    }

    private fun event(push: ScheduledPush, now: java.time.Instant): NotificationEvent {
        val type = if (push.advertising) properties.advertisingType else properties.type
        val branded = if (push.advertising) gate.brand(push.title, push.body, MAX_BRAND_BYTES) else null
        return NotificationEvent(
            topic = properties.topic,
            type = type,
            id = eventId(push.key),
            recipientIds = setOf(push.recipientId),
            title = branded?.title ?: push.title,
            message = branded?.body ?: push.body,
            createdAt = now,
            link = push.link,
            channels = setOf("push"),
        )
    }

    private companion object {
        val DAY: Duration = Duration.ofHours(24)
        const val MAX_BRAND_BYTES = 8000

        fun eventId(key: String): String =
            "sp_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))).take(32)
    }
}
