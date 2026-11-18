package dev.sumin.skeleton.notification.scheduled

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import dev.sumin.skeleton.common.wake.requestAfterCommit

/**
 * 앱이 부르는 입구 - 예약과 취소뿐이다. 계기(언제 예약하나)는 앱이 정한다.
 * [schedule] 은 같은 키면 새로 만들지 않고 아직 안 나간 예약을 바꾼다. 이미 나간 키는 `dedupe-window` 동안 [ScheduleResult.IGNORED] 로 그대로 둔다(같은 계기가 여러 번 일어나도 한 통) -
 * 반환값을 확인하라: 같은 사건을 다시 보내고 싶으면 키에 구분 값(회차 · 날짜)을 넣어야 한다.
 * 호출자의 트랜잭션에 묶이는지는 저장소 구현을 따른다(JDBC 는 호출자 트랜잭션에 참여한다).
 */
class ScheduledPushes(
    private val store: ScheduledPushStore,
    private val properties: ScheduledPushProperties,
    private val time: TimeProvider,
    /** 있으면 새로 만들거나 바꾼 예약의 시각에 깨우기가 불리게 한다(호출자 트랜잭션이 커밋된 뒤, 밖이면 바로). 인스턴스가 0대여도 때가 되면 훑기가 돈다 */
    private val wake: JobWake? = null,
) {
    fun schedule(push: ScheduledPush): ScheduleResult {
        require(!push.advertising || properties.advertisingType.isNotBlank()) {
            "advertising pushes need skeleton.notification.scheduled.advertising-type"
        }
        val result = store.upsert(push, time.now(), properties.dedupeWindow)
        if (result != ScheduleResult.IGNORED) wake?.requestAfterCommit(push.runAt)
        return result
    }

    /** 키로 취소한다 - 아직 안 나간 예약이 있었으면 true. 이미 나갔거나 없으면 false(멱등) */
    fun cancel(key: String): Boolean = store.cancel(key)

    /** 예약된 · 보낸 · 건너뛴 수 - 통계 화면이 읽는다 */
    fun stats(): ScheduledPushStats = store.stats()
}

/** 계정이 지워질 때 그 사람의 예약을 모두 지운다. 멱등 */
class ScheduledPushErasureListener(private val store: ScheduledPushStore) : AccountErasureListener {
    override val name: String = "notification-scheduled"

    override fun erase(request: ErasureRequest) {
        store.deleteByRecipient(request.accountId)
    }
}
