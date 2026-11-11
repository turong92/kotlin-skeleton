package dev.sumin.skeleton.idempotency.jdbc

import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.util.concurrent.TimeUnit

/**
 * 한 시간마다 만료한 멱등 키 줄을 지운다. `scheduler` 모듈이 있을 때 돈다(없으면 표시가 무시된다 - 요청 구동 모드의 `idempotency-purge` 작업이나 앱이 [JdbcIdempotencyStore.purgeExpired] 를 부른다).
 * 삭제가 멱등이라 여러 인스턴스가 겹쳐 돌려도 안전하다.
 */
class IdempotencyKeyPurge(private val store: JdbcIdempotencyStore) {
    @SkeletonScheduled(id = "idempotency.purge", fixedDelay = 1, timeUnit = TimeUnit.HOURS, lockKey = "idempotency.purge")
    fun run() {
        store.purgeExpired()
    }
}
