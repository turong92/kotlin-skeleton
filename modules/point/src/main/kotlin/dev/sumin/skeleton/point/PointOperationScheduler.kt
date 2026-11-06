package dev.sumin.skeleton.point

import java.time.Instant

/**
 * 불명 건 조회를 예약하는 자리 - `job-queue-jdbc` 가 있으면 point-jdbc 가 큐 어댑터를 낸다(없으면 이 빈이 없고 조회는 앱이 [PointOperations.resolve] 를 부른다).
 * [PointOperationLog.accept] 가 영수증을 쓰는 **같은 트랜잭션 안에서** 부른다 - 영수증이 저장됐으면 조회 작업도 반드시 있다(던지면 영수증도 사라지고 원장에 닿기 전에 멈춘다).
 */
fun interface PointOperationScheduler {
    fun schedule(operationId: String, runAt: Instant)
}
