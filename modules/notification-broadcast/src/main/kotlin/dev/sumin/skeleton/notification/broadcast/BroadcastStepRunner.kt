package dev.sumin.skeleton.notification.broadcast

import dev.sumin.skeleton.common.audience.AudienceSource
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingDecision
import dev.sumin.skeleton.notification.advertising.AdvertisingGate

/**
 * 단계 작업 하나 = 대상 한 묶음 (스펙 §4). 한 트랜잭션 안에서: 공지 줄을 잠그고 → 토큰 · 순번이 맞을 때만 → 대상을 키셋으로 한 쪽 읽고 →
 * (광고 종류면 동의 없는 사람을 뺀 뒤) 기존 `publish` 로 알림함 저장 → 커서 · 순번 · 수를 올리고 → 다음 작업을 넣는다.
 * 그래서 재시도 · 동시 실행 · 중복 작업은 순번이 이미 올라간 줄을 보고 아무것도 안 한다. 푸시 등 채널은 커밋 뒤에 브로커가 넘긴다(최선 노력 - 알림함이 원본).
 */
class BroadcastStepRunner internal constructor(
    private val store: BroadcastStore,
    private val tasks: BroadcastTasks,
    private val publisher: NotificationPublisher,
    private val sources: () -> List<AudienceSource>,
    private val gate: AdvertisingGate,
    private val properties: BroadcastProperties,
    private val time: TimeProvider,
    private val events: BroadcastEvents,
) {
    fun run(step: BroadcastStep) {
        store.transaction {
            val broadcast = store.findForUpdate(step.id) ?: return@transaction
            val runnable = broadcast.status == BroadcastStatus.SCHEDULED || broadcast.status == BroadcastStatus.SENDING
            if (!runnable || broadcast.runToken != step.token || broadcast.nextSeq != step.seq) return@transaction
            val audience = broadcast.content.audience
            val source = sources().firstOrNull { it.kind == audience.kind }
                ?: throw BroadcastPermanentFailure("audience kind '${audience.kind}' is no longer available")
            val now = time.now()
            val after = broadcast.cursorId
            val page = source.page(audience.param, after, properties.batchSize).filter { after == null || it > after }.distinct()
            if (page.isEmpty()) {
                store.finish(broadcast.id, now)
                return@transaction
            }
            // 광고성: 동의 없는 사람은 알림함 · 푸시 모두에서 뺀다. 정보성은 전원. 받는 사람이 빈 이벤트는 「전체 공지」로 읽히므로 비면 발행하지 않는다
            val recipients = if (gate.isAdvertising(broadcast.type)) page.filter { gate.decide(it) != AdvertisingDecision.Skip } else page
            if (recipients.isNotEmpty()) {
                publisher.publish(events.event(broadcast, broadcast.id, recipients.toSet(), "", now))
            }
            store.advance(broadcast.id, page.max(), page.size, now)
            tasks.enqueue(BroadcastStep(broadcast.id, step.token, step.seq + 1), now.plus(properties.stepInterval))
        }
    }

    /** 이 토큰의 작업이 죽었다(재시도를 다 썼다 · 영구 실패) — 공지를 FAILED 로. [reason] 은 예외 종류만 남긴다(메시지에 받는 사람이 섞일 수 있다) */
    fun fail(step: BroadcastStep, reason: String) {
        store.fail(step.id, step.token, reason.substringBefore(':').take(MAX_REASON), time.now())
    }

    private companion object {
        const val MAX_REASON = 200
    }
}
