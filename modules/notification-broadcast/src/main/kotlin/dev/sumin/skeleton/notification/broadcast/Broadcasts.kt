package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.authz.OperatorAction
import dev.sumin.skeleton.common.authz.OperatorAuditLog
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationInboxRepository
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 공지 운영(만들기 · 보내기 · 예약 · 취소 · 이어 보내기 · 테스트 · 통계). 상태 전이는 모두 저장소의 조건부 갱신이라 두 번 누름 · 동시 요청에서 하나만 이긴다(나머지 409).
 * 성공한 보내기 · 예약 · 취소 · 이어 보내기는 같은 트랜잭션 안에서 [OperatorAuditLog] 에 남는다. 보내는 몸체는 [BroadcastStepRunner].
 */
class Broadcasts internal constructor(
    private val store: BroadcastStore,
    private val tasks: BroadcastTasks,
    private val publisher: NotificationPublisher,
    private val inbox: NotificationInboxRepository,
    private val sources: () -> List<AudienceSource>,
    private val channelNames: () -> Set<String>,
    private val gate: AdvertisingGate,
    private val pushTypes: Set<String>,
    private val properties: BroadcastProperties,
    private val audit: OperatorAuditLog,
    private val time: TimeProvider,
    private val events: BroadcastEvents,
    private val ids: SortableIds = SortableIds.system,
) {
    private val lastTest = ConcurrentHashMap<String, Instant>()

    fun options(): BroadcastOptions = BroadcastOptions(
        types = properties.typeList.map { BroadcastTypeOption(it, advertising = gate.isAdvertising(it), push = gate.isAdvertising(it) || it in pushTypes) },
        channels = channelNames().sorted(),
        audiences = sources().map { BroadcastAudienceOption(it.kind, it.param) },
    )

    fun audienceCount(kind: String, param: String?): Long {
        val audience = checkedAudience(BroadcastAudience(kind, param))
        return sourceOf(audience.kind).count(audience.param)
    }

    fun create(actor: String, input: BroadcastContent): Broadcast {
        val now = time.now()
        val broadcast = Broadcast(
            id = ids.newId(BroadcastIds.PREFIX), status = BroadcastStatus.DRAFT, content = checked(input),
            scheduledAt = null, runToken = null, nextSeq = 0, cursorId = null, estimate = null, recipients = 0,
            createdBy = actor, sentBy = null, cancelledBy = null, createdAt = now, updatedAt = now,
            sendRequestedAt = null, startedAt = null, finishedAt = null, cancelledAt = null, failureReason = null,
        )
        store.insert(broadcast)
        return broadcast
    }

    fun update(id: String, input: BroadcastContent): Broadcast {
        val content = checked(input)
        if (!store.updateDraft(id, content, time.now())) throw missingOrState(id)
        return requireNotNull(store.find(id))
    }

    fun delete(id: String) {
        if (!store.deleteDraft(id)) throw missingOrState(id)
    }

    fun get(id: String): BroadcastDetail {
        val broadcast = store.find(id) ?: throw notFound(id)
        return BroadcastDetail(broadcast, inbox.eventStats(id), store.channelStats(id))
    }

    fun list(status: BroadcastStatus?, before: String?, limit: Int): List<Broadcast> =
        store.list(status, before?.takeIf { it.isNotBlank() }, limit.coerceIn(1, MAX_LIST))

    /** 지금 보내거나(`scheduledAt` 없음) 예약한다. DRAFT 만 — 아니면 409, 예약은 지금 ~ `max-schedule-ahead` 안만 */
    fun send(actor: String, id: String, scheduledAt: Instant?) {
        store.transaction {
            val broadcast = store.find(id) ?: throw notFound(id)
            if (broadcast.status != BroadcastStatus.DRAFT) throw invalidState(broadcast.status)
            val now = time.now()
            if (scheduledAt != null && (scheduledAt.isBefore(now) || scheduledAt.isAfter(now.plus(properties.maxScheduleAhead)))) {
                throw BroadcastException(BroadcastErrorCode.INVALID_SCHEDULE, "scheduledAt must be between now and ${properties.maxScheduleAhead} from now")
            }
            val audience = checkedAudience(broadcast.content.audience)
            val token = UUID.randomUUID().toString().replace("-", "")
            if (!store.start(id, token, scheduledAt, sourceOf(audience.kind).count(audience.param), actor, now)) throw invalidState(null)
            store.openChannels(id, broadcast.content.channels)
            tasks.enqueue(BroadcastStep(id, token, 0), scheduledAt)
            record(actor, if (scheduledAt != null) "scheduled" else "sent", id)
        }
    }

    /** 예약 풀기 — SCHEDULED → DRAFT. 큐에 남은 옛 작업은 토큰이 달라 아무것도 하지 않는다 */
    fun unschedule(id: String) {
        if (!store.unschedule(id, time.now())) throw missingOrState(id)
    }

    /** 취소 — 더 보내지 않는다(큐 속 푸시 포함). 이미 알림함에 들어간 줄은 남는다 */
    fun cancel(actor: String, id: String) {
        store.transaction {
            if (!store.cancel(id, actor, time.now())) throw missingOrState(id)
            record(actor, "cancelled", id)
        }
    }

    /** 이어 보내기 — FAILED → SENDING, 같은 커서부터 새 토큰으로 */
    fun resume(actor: String, id: String) {
        store.transaction {
            val broadcast = store.findForUpdate(id) ?: throw notFound(id)
            if (broadcast.status != BroadcastStatus.FAILED) throw invalidState(broadcast.status)
            val token = UUID.randomUUID().toString().replace("-", "")
            if (!store.resume(id, token, time.now())) throw invalidState(null)
            tasks.enqueue(BroadcastStep(id, token, broadcast.nextSeq), null)
            record(actor, "resumed", id)
        }
    }

    /** 나에게만 지금 내용으로 — 제목 앞 [BroadcastProperties.testTitlePrefix], 통계 밖(`bct_`), 운영자당 [BroadcastProperties.testCooldown] 에 한 번 */
    fun test(actor: String, id: String) {
        val broadcast = store.find(id) ?: throw notFound(id)
        val now = time.now()
        var tooSoon = false
        lastTest.compute(actor) { _, last ->
            if (last != null && now.isBefore(last.plus(properties.testCooldown))) { tooSoon = true; last } else now
        }
        if (tooSoon) throw BroadcastException(BroadcastErrorCode.TEST_TOO_SOON, "Wait ${properties.testCooldown.seconds}s between tests")
        publisher.publish(events.event(broadcast, ids.newId(BroadcastIds.TEST_PREFIX), setOf(actor), properties.testTitlePrefix, now))
    }

    // -----------------------------------------------------------------------------------------------------------------

    private fun checked(input: BroadcastContent): BroadcastContent {
        val type = input.type.trim()
        if (type !in properties.typeList) throw BroadcastException(BroadcastErrorCode.UNKNOWN_TYPE, "Unknown broadcast type '$type'")
        val title = input.title.trim()
        if (title.isEmpty()) throw FieldValidationException("title", "Required", "title must not be blank")
        if (title.length > MAX_TITLE) throw FieldValidationException("title", "Size", "title must be at most $MAX_TITLE characters")
        val body = input.body?.trim()?.takeIf { it.isNotEmpty() }
        if (body != null && body.length > MAX_BODY) throw FieldValidationException("body", "Size", "body must be at most $MAX_BODY characters")
        val link = input.link?.trim()?.takeIf { it.isNotEmpty() }
        if (link != null && !NotificationEvent.isValidLink(link)) throw BroadcastException(BroadcastErrorCode.INVALID_LINK, "link must be an in-app path")
        val declared = channelNames()
        val channels = input.channels.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        channels.firstOrNull { it !in declared }?.let { throw BroadcastException(BroadcastErrorCode.UNKNOWN_CHANNEL, "Unknown channel '$it'") }
        return BroadcastContent(type, title, body, link, channels, checkedAudience(input.audience))
    }

    private fun checkedAudience(raw: BroadcastAudience): BroadcastAudience {
        val kind = raw.kind.trim()
        val param = raw.param?.trim()?.takeIf { it.isNotEmpty() }
        val source = sources().firstOrNull { it.kind == kind } ?: throw BroadcastException(BroadcastErrorCode.UNKNOWN_AUDIENCE, "Unknown audience kind '$kind'")
        source.validate(param)?.let { throw BroadcastException(BroadcastErrorCode.INVALID_AUDIENCE, it, mapOf("reason" to it)) }
        return BroadcastAudience(kind, param)
    }

    private fun sourceOf(kind: String): AudienceSource = sources().first { it.kind == kind }

    private fun record(actor: String, verb: String, id: String) =
        audit.record(OperatorAction(actor, "notification-broadcast.notice.$verb", "broadcast", id))

    private fun missingOrState(id: String): BroadcastException =
        store.find(id)?.let { invalidState(it.status) } ?: notFound(id)

    private fun notFound(id: String) = BroadcastException(BroadcastErrorCode.NOT_FOUND, "Broadcast not found: $id")

    private fun invalidState(status: BroadcastStatus?) =
        BroadcastException(BroadcastErrorCode.INVALID_STATE, "Not allowed while the broadcast is ${status ?: "being changed by someone else"}")

    private companion object {
        const val MAX_TITLE = 100
        const val MAX_BODY = 2000
        const val MAX_LIST = 100
    }
}
