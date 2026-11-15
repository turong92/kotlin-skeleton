package dev.sumin.skeleton.notification.scheduled

import java.time.Duration
import java.time.Instant

/**
 * 예약 저장소 포트(구현: `notification-scheduled-jdbc`). 상태를 바꾸는 메서드는 모두 **조건부 갱신**이라 여러 인스턴스가 같은 줄을 만져도 하나만 이긴다.
 * 끝난 줄(SENT · SKIPPED)은 같은 키의 중복 예약을 막는 표시로 [purgeFinishedBefore] 때까지 남는다.
 */
interface ScheduledPushStore {
    /**
     * 같은 키가 없으면 넣고([ScheduleResult.CREATED]), 아직 안 나간(PENDING · 집히지 않은) 줄이면 내용 · 시각을 바꾸고([ScheduleResult.REPLACED]),
     * [dedupeWindow] 보다 오래전에 끝난 줄이면 새 예약으로 되살리고([ScheduleResult.CREATED]), 그 밖(최근에 끝났거나 집혀 나가는 중)이면 [ScheduleResult.IGNORED].
     */
    fun upsert(push: ScheduledPush, now: Instant, dedupeWindow: Duration): ScheduleResult

    /** 아직 나가지 않은(PENDING) 줄만 지운다. 이미 집혀 나가는 중이면 막지 못한다 */
    fun cancel(key: String): Boolean

    /**
     * 시각이 된 PENDING 줄을 [limit] 개까지 집는다 - 집은 줄은 [lease] 동안 다른 훑기가 못 집는다(집은 쪽이 죽으면 그 뒤 다시 집힌다). 이긴 훑기만 그 줄을 받고,
     * 받은 [DuePush.claimToken] 이 이 집기의 소유 증표다.
     */
    fun claimDue(now: Instant, lease: Duration, limit: Int): List<DuePush>

    /** 집은 줄을 보냈다 / 건너뛰었다고 닫는다 - 아직 PENDING 이고 [claimToken] 이 줄의 집기와 같을 때만(남의 집기 · 바뀐 예약은 건드리지 않는다) */
    fun finish(key: String, claimToken: Instant, status: ScheduledPushStatus, reason: SkipReason?, now: Instant)

    /** 집은 줄을 놓고 [runAt] 으로 미룬다(야간 보류) - [claimToken] 이 같을 때만 */
    fun reschedule(key: String, claimToken: Instant, runAt: Instant, now: Instant)

    /** [recipientId] 에게 [since] 이후 보낸(SENT) 건수 - 하루 상한 판정 */
    fun sentCountSince(recipientId: String, since: Instant): Long

    /** 그 사람의 예약을 모두 지운다(탈퇴). 멱등 */
    fun deleteByRecipient(recipientId: String): Int

    /** [cutoff] 보다 먼저 끝난 줄을 지운다. 멱등 */
    fun purgeFinishedBefore(cutoff: Instant): Int

    fun stats(): ScheduledPushStats
}
