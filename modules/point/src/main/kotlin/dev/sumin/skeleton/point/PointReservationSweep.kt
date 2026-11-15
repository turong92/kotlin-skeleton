package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.util.concurrent.TimeUnit

/**
 * 1분마다 기한이 지난 잡힌 건을 푼다. `scheduler` 모듈이 있을 때 돈다(없으면 표시가 무시되고 요청 구동 모드의 `point-reservation-sweep` 이 부른다 - 둘 다 없으면 잡아 두기를 켠 앱의 시작이 실패한다).
 * 여러 인스턴스가 동시에 돌려도 [PointStore.expireReservations] 가 건마다 조건부로 한 번만 풀어 안전하다.
 */
class PointReservationSweep(private val wallet: PointWallet, private val time: TimeProvider) {
    fun run(): Int = wallet.expireReservations(time.now())

    @SkeletonScheduled(id = "point.reservation-sweep", fixedDelay = 1, timeUnit = TimeUnit.MINUTES, lockKey = "point.reservation-sweep")
    fun scheduled() {
        run()
    }
}
