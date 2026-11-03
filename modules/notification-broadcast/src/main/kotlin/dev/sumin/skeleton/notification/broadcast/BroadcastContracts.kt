package dev.sumin.skeleton.notification.broadcast

import java.time.Instant

/**
 * 공지 저장소 포트(구현: `notification-broadcast-jdbc`). 상태를 바꾸는 메서드는 모두 **조건부 갱신**이고 바꿨으면 true — 두 번 누름 · 동시 요청에서 하나만 이기게 한다.
 * [transaction] 안의 호출(알림함 저장 · 큐에 넣기 포함)은 한 트랜잭션으로 묶인다.
 */
interface BroadcastStore {
    fun <T> transaction(block: () -> T): T

    fun insert(broadcast: Broadcast)

    fun find(id: String): Broadcast?

    /** 줄을 잠그고 읽는다 — [transaction] 안에서만. 단계 작업이 같은 공지의 다른 작업 · 취소와 겹치지 않게 한다 */
    fun findForUpdate(id: String): Broadcast?

    /** 지금 상태만(싼 읽기) — 전달 고리가 5초 캐시로 묻는다 */
    fun statusOf(id: String): BroadcastStatus?

    /** 최근 순(id 내림차순) 키셋: [before] 보다 작은 id */
    fun list(status: BroadcastStatus?, before: String?, limit: Int): List<Broadcast>

    /** DRAFT 만 */
    fun updateDraft(id: String, content: BroadcastContent, at: Instant): Boolean

    /** DRAFT 만 */
    fun deleteDraft(id: String): Boolean

    /** DRAFT → [scheduledAt] 이 있으면 SCHEDULED, 없으면 SENDING. 토큰 · 예상 수 · 순번 0 을 함께 쓴다 */
    fun start(id: String, token: String, scheduledAt: Instant?, estimate: Long, by: String, at: Instant): Boolean

    /** SCHEDULED → DRAFT (토큰을 지워 옛 작업이 아무것도 못 하게 한다) */
    fun unschedule(id: String, at: Instant): Boolean

    /** SCHEDULED | SENDING → CANCELLED */
    fun cancel(id: String, by: String, at: Instant): Boolean

    /** FAILED → SENDING, 새 토큰 — 순번과 커서는 그대로(같은 자리부터) */
    fun resume(id: String, token: String, at: Instant): Boolean

    /** 이 [token] 의 작업이 죽었다 — 아직 SCHEDULED | SENDING 이고 토큰이 같을 때만 FAILED */
    fun fail(id: String, token: String, reason: String, at: Instant): Boolean

    /** 한 묶음을 끝냈다: SENDING 으로, 커서 · 순번 +1 · 처리한 사람 수 더하기 · 처음이면 시작 시각 */
    fun advance(id: String, cursorId: String, processed: Int, at: Instant)

    /** 대상이 끝났다: SENT · 끝난 시각 · 커서 지움 */
    fun finish(id: String, at: Instant)

    /** 보낼 때 고른 채널마다 통계 줄 하나(이미 있으면 새로 시작) */
    fun openChannels(id: String, channels: List<String>)

    fun channelStats(id: String): Map<String, ChannelStats>

    /** 원자 증가 — 그 채널 줄이 없으면(고르지 않은 채널) 아무 일도 안 한다 */
    fun bump(id: String, channel: String, field: StatField, by: Int = 1)
}

/** 단계 작업을 큐에 넣는 포트(구현: `notification-broadcast-jdbc` 가 `job-queue-jdbc` 에 싣는다). 호출자 트랜잭션에 함께 묶인다 */
interface BroadcastTasks {
    fun enqueue(step: BroadcastStep, runAt: Instant?)
}

/** 다시 해도 소용없는 실패(대상 종류가 없어짐 …) — 큐가 재시도 없이 바로 DEAD 로 보낸다 */
class BroadcastPermanentFailure(message: String) : RuntimeException(message)
