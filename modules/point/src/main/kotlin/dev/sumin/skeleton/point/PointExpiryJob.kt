package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.scheduler.SkeletonScheduled

/**
 * 하루 한 번 유효기간이 지난 묶음을 `EXPIRE` 줄로 닫는다. `scheduler` 모듈이 있을 때 돈다(없으면 표시가 무시된다) — 시각은 그 모듈의 기본 시간대.
 * 여러 인스턴스가 동시에 돌려도 [PointStore.expireDue] 가 멱등이라 안전하다. 쓰는 쪽은 소멸 작업이 늦어도 지난 묶음을 못 쓴다(저장소가 쓰기 때마다 그 계정 몫을 먼저 닫는다).
 */
class PointExpiryJob(private val wallet: PointWallet, private val time: TimeProvider) {
    @SkeletonScheduled(id = "point.expire", localTime = "03:30", lockKey = "point.expire")
    fun run() {
        wallet.expireDue(time.now())
    }
}
