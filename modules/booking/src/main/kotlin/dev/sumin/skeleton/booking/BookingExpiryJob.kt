package dev.sumin.skeleton.booking

import dev.sumin.skeleton.scheduler.SkeletonScheduled

/**
 * 1분마다 시간이 지난 보류를 회수한다. `scheduler` 모듈이 있을 때 돈다(없으면 표시가 무시된다 — 요청 구동 모드는 [dev.sumin.skeleton.common.wake.OnRequestTask] 가 깨운다).
 * 이 주기가 늦어도 자리는 안 막힌다 — 잡기가 막히면 그 자리에서 회수하고, 화면은 사건에 실린 만료 시각에 스스로 「열림」으로 바꾼다.
 */
class BookingExpiryJob(private val holds: BookingHolds) {
    @SkeletonScheduled(id = "booking.hold-expire", fixedDelay = 60_000, initialDelay = 60_000, lockKey = "booking.hold-expire")
    fun run() {
        holds.expireDue()
    }
}
