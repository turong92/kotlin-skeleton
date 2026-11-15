package dev.sumin.skeleton.audit.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * [keep] 보다 오래된 운영 기록을 [batch] 줄씩 끊어 지운다 — 긴 삭제 하나가 표를 오래 잡지 않게. 로그에는 지운 건수만 싣는다.
 * 삭제가 멱등이라 여러 인스턴스가 겹쳐 돌려도 안전하다. 켜는 것은 `skeleton.audit.retention` 이다(기본 꺼짐 — 아무것도 지우지 않는다).
 * 데이터베이스가 30일이 안 된 줄은 지우지 못하게 하므로 [keep] 이 30일 이상이어야 한다([AuditProperties]).
 */
class AuditRetention(
    private val store: AuditLogStore,
    private val keep: Duration,
    private val time: TimeProvider,
    private val batch: Int = 1000,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun purge(): Int {
        // 하루 여유: 앱 시계와 데이터베이스 시계가 어긋나도 keep 이 정확히 30일일 때 트리거의 30일 경계에 걸려 삭제가 거절되지 않게
        val cutoff = time.now().minus(keep).minus(CLOCK_MARGIN)
        var total = 0
        while (true) {
            val deleted = store.purgeBefore(cutoff, batch)
            total += deleted
            if (deleted < batch) break
        }
        if (total > 0) log.info("audit retention: deleted {} operator audit lines older than {}", total, keep)
        return total
    }

    private companion object {
        val CLOCK_MARGIN: Duration = Duration.ofDays(1)
    }

    /** `scheduler` 모듈이 있을 때 하루 한 번 돈다(없으면 표시가 무시된다 - 요청 구동 모드는 `audit-retention` 작업이 부른다) */
    @SkeletonScheduled(id = "audit.retention", localTime = "04:20", lockKey = "audit.retention")
    fun scheduled() {
        purge()
    }
}
