package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.notification.NotificationPublisher
import java.time.Duration
import java.time.Instant

val T0: Instant = Instant.parse("2026-11-02T03:00:00Z")   // 12:00 KST

/** 저장소 대역 - 조건부 전이를 진짜처럼 흉내 낸다(한 줄 = 한 객체, 잠금은 synchronized) */
class FakeScheduledPushStore : ScheduledPushStore {
    class Row(var push: ScheduledPush, var status: ScheduledPushStatus = ScheduledPushStatus.PENDING, var reason: SkipReason? = null, var claimedUntil: Instant? = null, var attempts: Int = 0, var finishedAt: Instant? = null)

    val rows = linkedMapOf<String, Row>()

    @Synchronized
    override fun upsert(push: ScheduledPush, now: Instant, dedupeWindow: Duration): ScheduleResult {
        val row = rows[push.key]
        if (row == null) {
            rows[push.key] = Row(push)
            return ScheduleResult.CREATED
        }
        if (row.status != ScheduledPushStatus.PENDING && row.finishedAt!!.isBefore(now.minus(dedupeWindow))) {
            rows[push.key] = Row(push)   // 중복 막기 기간이 지난 키는 새 예약으로 되살아난다
            return ScheduleResult.CREATED
        }
        if (row.status != ScheduledPushStatus.PENDING || (row.claimedUntil?.isAfter(now) == true)) return ScheduleResult.IGNORED
        row.push = push
        return ScheduleResult.REPLACED
    }

    @Synchronized
    override fun cancel(key: String): Boolean = rows[key]?.takeIf { it.status == ScheduledPushStatus.PENDING }?.let { rows.remove(key); true } ?: false

    @Synchronized
    override fun claimDue(now: Instant, lease: Duration, limit: Int): List<DuePush> =
        rows.values
            .filter { it.status == ScheduledPushStatus.PENDING && !it.push.runAt.isAfter(now) && (it.claimedUntil == null || !it.claimedUntil!!.isAfter(now)) }
            .sortedBy { it.push.runAt }.take(limit)
            .map { it.claimedUntil = now.plus(lease); it.attempts++; DuePush(it.push, it.attempts, it.claimedUntil!!) }

    @Synchronized
    override fun finish(key: String, claimToken: Instant, status: ScheduledPushStatus, reason: SkipReason?, now: Instant) {
        rows[key]?.takeIf { it.status == ScheduledPushStatus.PENDING && it.claimedUntil == claimToken }?.let { it.status = status; it.reason = reason; it.claimedUntil = null; it.finishedAt = now }
    }

    @Synchronized
    override fun reschedule(key: String, claimToken: Instant, runAt: Instant, now: Instant) {
        rows[key]?.takeIf { it.status == ScheduledPushStatus.PENDING && it.claimedUntil == claimToken }?.let { it.push = it.push.copy(runAt = runAt); it.claimedUntil = null }
    }

    @Synchronized
    override fun sentCountSince(recipientId: String, since: Instant): Long =
        rows.values.count { it.status == ScheduledPushStatus.SENT && it.push.recipientId == recipientId && !it.finishedAt!!.isBefore(since) }.toLong()

    @Synchronized
    override fun deleteByRecipient(recipientId: String): Int {
        val keys = rows.filterValues { it.push.recipientId == recipientId }.keys.toList()
        keys.forEach { rows.remove(it) }
        return keys.size
    }

    @Synchronized
    override fun purgeFinishedBefore(cutoff: Instant): Int = 0

    @Synchronized
    override fun stats() = ScheduledPushStats(
        rows.values.count { it.status == ScheduledPushStatus.PENDING }.toLong(),
        rows.values.count { it.status == ScheduledPushStatus.SENT }.toLong(),
        rows.values.count { it.status == ScheduledPushStatus.SKIPPED }.toLong(),
    )
}

class RecordingPublisher : NotificationPublisher {
    val events = java.util.concurrent.CopyOnWriteArrayList<NotificationEvent>()
    var failNext = false

    override fun publish(event: NotificationEvent): NotificationPublishResult {
        if (failNext) {
            failNext = false
            error("broker down")
        }
        events += event
        return NotificationPublishResult(event.id, 1)
    }
}
