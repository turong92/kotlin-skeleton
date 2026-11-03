package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.DeliveryOutcome
import dev.sumin.skeleton.notification.NotificationDeliveryHook
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 채널(푸시 …)의 전달 과정을 공지 통계로 모은다 — 공지 모듈은 푸시를 모르고 고리로만 듣는다. 접두사 `bc_` 인 이벤트만 센다(`bct_` 테스트 발송은 통계 밖).
 * [halted]: 취소된 공지의 큐 속 푸시를 멈춘다. 보내기마다 불리므로 상태를 [cacheFor](5초) 동안 기억한다 - 취소 뒤 그 안에 나간 것은 나갈 수 있다.
 */
class BroadcastDeliveryHook(
    private val store: BroadcastStore,
    private val time: TimeProvider,
    private val cacheFor: Duration = Duration.ofSeconds(5),
) : NotificationDeliveryHook {
    private class Seen(val at: Instant, val halted: Boolean)

    private val seen = ConcurrentHashMap<String, Seen>()

    override fun halted(eventId: String): Boolean {
        if (!ours(eventId)) return false
        val now = time.now()
        seen[eventId]?.let { if (now.isBefore(it.at.plus(cacheFor))) return it.halted }
        val halted = store.statusOf(eventId) == BroadcastStatus.CANCELLED
        if (seen.size >= MAX_CACHED) seen.entries.removeIf { !now.isBefore(it.value.at.plus(cacheFor)) }
        seen[eventId] = Seen(now, halted)
        return halted
    }

    override fun onQueued(eventId: String, channel: String, count: Int) = bump(eventId, channel, StatField.QUEUED, count)

    override fun onOutcome(eventId: String, channel: String, outcome: DeliveryOutcome) =
        bump(eventId, channel, when (outcome) {
            DeliveryOutcome.DELIVERED -> StatField.DELIVERED
            DeliveryOutcome.FAILED -> StatField.FAILED
            DeliveryOutcome.DROPPED -> StatField.DROPPED
        }, 1)

    override fun onOpened(eventId: String, channel: String) = bump(eventId, channel, StatField.OPENED, 1)

    private fun bump(eventId: String, channel: String, field: StatField, by: Int) {
        if (ours(eventId)) store.bump(eventId, channel, field, by)
    }

    private fun ours(eventId: String) = eventId.startsWith(BroadcastIds.PREFIX)

    private companion object {
        const val MAX_CACHED = 1000
    }
}
