package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * [keep] 보다 오래된 광고 세션 · 하루 카운터 줄을 [batch] 줄씩 끊어 지운다 — 긴 삭제 하나가 표를 오래 잡지 않게. 로그에는 지운 건수만 싣는다.
 * 아직 쓸 수 있는 토큰이 있는 세션은 지우지 않는다([WebRewardStore.purgeBefore]). 삭제가 멱등이라 여러 인스턴스가 겹쳐 돌려도 안전하다.
 * 켜는 것은 `skeleton.ads-web-rewarded.retention` 이다(기본 꺼짐 — 아무것도 지우지 않는다).
 */
class WebRewardRetention(
    private val store: WebRewardStore,
    private val keep: Duration,
    private val time: TimeProvider,
    private val batch: Int = 1000,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun purge(): Int {
        val cutoff = time.now().minus(keep)
        var total = 0
        while (true) {
            val deleted = store.purgeBefore(cutoff, batch)
            total += deleted
            if (deleted < batch) break
        }
        if (total > 0) log.info("ads-web-rewarded retention: deleted {} session and counter lines older than {}", total, keep)
        return total
    }

    /** `scheduler` 모듈이 있을 때 하루 한 번 돈다(없으면 표시가 무시된다 - 요청 구동 모드는 `ads-web-rewarded-retention` 작업이 부른다) */
    @SkeletonScheduled(id = "ads-web-rewarded.retention", localTime = "04:40", lockKey = "ads-web-rewarded.retention")
    fun scheduled() {
        purge()
    }
}
