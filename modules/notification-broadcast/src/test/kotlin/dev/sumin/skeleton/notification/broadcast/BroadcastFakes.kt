package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.audience.AudienceParam
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublishResult
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingConsent
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

val T0: Instant = Instant.parse("2026-11-02T00:00:00Z")

/** 저장소 대역 - 조건부 전이를 진짜처럼 흉내 낸다(한 줄 = 한 객체, 잠금은 synchronized) */
class FakeBroadcastStore : BroadcastStore {
    val rows = linkedMapOf<String, Broadcast>()
    val stats = linkedMapOf<String, MutableMap<String, ChannelStats>>()

    @Synchronized override fun <T> transaction(block: () -> T): T = block()
    @Synchronized override fun insert(broadcast: Broadcast) { rows[broadcast.id] = broadcast }
    @Synchronized override fun find(id: String) = rows[id]
    @Synchronized override fun findForUpdate(id: String) = rows[id]
    @Synchronized override fun statusOf(id: String) = rows[id]?.status
    @Synchronized override fun list(status: BroadcastStatus?, before: String?, limit: Int) =
        rows.values.filter { (status == null || it.status == status) && (before == null || it.id < before) }.sortedByDescending { it.id }.take(limit)

    @Synchronized private fun change(id: String, from: Set<BroadcastStatus>, to: (Broadcast) -> Broadcast): Boolean {
        val row = rows[id]?.takeIf { it.status in from } ?: return false
        rows[id] = to(row)
        return true
    }

    override fun updateDraft(id: String, content: BroadcastContent, at: Instant) = change(id, setOf(BroadcastStatus.DRAFT)) { it.copy(content = content, updatedAt = at) }
    @Synchronized override fun deleteDraft(id: String) = rows[id]?.takeIf { it.status == BroadcastStatus.DRAFT }?.let { rows.remove(id); true } ?: false
    override fun start(id: String, token: String, scheduledAt: Instant?, estimate: Long, by: String, at: Instant) =
        change(id, setOf(BroadcastStatus.DRAFT)) {
            it.copy(status = if (scheduledAt != null) BroadcastStatus.SCHEDULED else BroadcastStatus.SENDING, runToken = token, nextSeq = 0, cursorId = null, estimate = estimate, recipients = 0, scheduledAt = scheduledAt, sentBy = by, sendRequestedAt = at)
        }
    override fun unschedule(id: String, at: Instant) = change(id, setOf(BroadcastStatus.SCHEDULED)) { it.copy(status = BroadcastStatus.DRAFT, runToken = null, nextSeq = 0, scheduledAt = null, estimate = null) }
    override fun cancel(id: String, by: String, at: Instant) = change(id, setOf(BroadcastStatus.SCHEDULED, BroadcastStatus.SENDING)) { it.copy(status = BroadcastStatus.CANCELLED, cancelledBy = by, cancelledAt = at) }
    override fun resume(id: String, token: String, at: Instant) = change(id, setOf(BroadcastStatus.FAILED)) { it.copy(status = BroadcastStatus.SENDING, runToken = token, failureReason = null) }
    @Synchronized override fun fail(id: String, token: String, reason: String, at: Instant) =
        rows[id]?.takeIf { it.runToken == token }?.let { change(id, setOf(BroadcastStatus.SCHEDULED, BroadcastStatus.SENDING)) { r -> r.copy(status = BroadcastStatus.FAILED, failureReason = reason) } } ?: false
    override fun advance(id: String, cursorId: String, processed: Int, at: Instant) {
        change(id, setOf(BroadcastStatus.SCHEDULED, BroadcastStatus.SENDING)) { it.copy(status = BroadcastStatus.SENDING, cursorId = cursorId, nextSeq = it.nextSeq + 1, recipients = it.recipients + processed, startedAt = it.startedAt ?: at) }
    }
    override fun finish(id: String, at: Instant) {
        change(id, setOf(BroadcastStatus.SCHEDULED, BroadcastStatus.SENDING)) { it.copy(status = BroadcastStatus.SENT, cursorId = null, finishedAt = at) }
    }
    @Synchronized override fun openChannels(id: String, channels: List<String>) { stats[id] = channels.associateWith { ChannelStats() }.toMutableMap() }
    @Synchronized override fun channelStats(id: String): Map<String, ChannelStats> = stats[id]?.toMap().orEmpty()
    @Synchronized override fun bump(id: String, channel: String, field: StatField, by: Int) {
        val s = stats[id]?.get(channel) ?: return
        stats[id]!![channel] = when (field) {
            StatField.QUEUED -> s.copy(queued = s.queued + by)
            StatField.DELIVERED -> s.copy(delivered = s.delivered + by)
            StatField.FAILED -> s.copy(failed = s.failed + by)
            StatField.DROPPED -> s.copy(dropped = s.dropped + by)
            StatField.OPENED -> s.copy(opened = s.opened + by)
        }
    }
}

class RecordingTasks : BroadcastTasks {
    val steps = CopyOnWriteArrayList<Pair<BroadcastStep, Instant?>>()
    override fun enqueue(step: BroadcastStep, runAt: Instant?) { steps += step to runAt }
}

class RecordingPublisher : NotificationPublisher {
    val events = CopyOnWriteArrayList<NotificationEvent>()
    override fun publish(event: NotificationEvent): NotificationPublishResult { events += event; return NotificationPublishResult(event.id, 0) }
}

/** 계정 id 오름차순 키셋을 내는 대상 대역 */
class StaticAudience(override val kind: String, private val ids: List<String>) : AudienceSource {
    override val param = AudienceParam.NONE
    override fun validate(param: String?): String? = null
    override fun count(param: String?) = ids.size.toLong()
    override fun page(param: String?, after: String?, limit: Int) = ids.sorted().filter { after == null || it > after }.take(limit)
}

/** 광고 종류 `promo.x`, 동의는 [consenting] 에 든 사람만(야간 동의도 있어 보류 없음) */
fun advertisingGate(consenting: Set<String>, advertisingTypes: List<String> = listOf("promo.x"), suffix: String = ""): AdvertisingGate =
    AdvertisingGate(
        AdvertisingProperties(types = advertisingTypes, fallbackZone = "Asia/Seoul", senderName = "Acme", bodySuffix = suffix),
        { id, _ -> AdvertisingConsent(send = id in consenting, night = true) },
        null,
        TimeProvider.fixed(T0),
    )

fun plainGate(): AdvertisingGate = AdvertisingGate(AdvertisingProperties(), null, null, TimeProvider.fixed(T0))

fun draft(type: String = "notice.service", audienceKind: String = "everyone", channels: List<String> = emptyList(), id: String = "bc_1") = Broadcast(
    id = id, status = BroadcastStatus.DRAFT,
    content = BroadcastContent(type, "Maintenance", "Tonight 2am", "/notices/1", channels, BroadcastAudience(audienceKind)),
    scheduledAt = null, runToken = null, nextSeq = 0, cursorId = null, estimate = null, recipients = 0,
    createdBy = "acc_op", sentBy = null, cancelledBy = null, createdAt = T0, updatedAt = T0,
    sendRequestedAt = null, startedAt = null, finishedAt = null, cancelledAt = null, failureReason = null,
)
