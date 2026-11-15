package dev.sumin.skeleton.inquiry

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.scheduler.SkeletonScheduled
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * 처리 완료(DONE) 뒤 [keep] 이 지난 문의를 지운다 - 미처리(NEW) · 되돌린 문의는 남는다. 로그에는 지운 건수만 싣는다(본문 · 이메일 없음).
 * 삭제가 멱등이라 여러 인스턴스가 겹쳐 돌려도 안전하다. 켜는 것은 `skeleton.inquiry.retention` 이다.
 */
class InquiryRetention(
    private val repository: InquiryRepository,
    private val keep: Duration,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun purge(): Int = repository.deleteHandledBefore(time.now().minus(keep)).also {
        if (it > 0) log.info("inquiry retention: deleted {} handled inquiries", it)
    }

    /** `scheduler` 모듈이 있을 때 하루 한 번 돈다(없으면 표시가 무시된다 - 요청 구동 모드는 `inquiry-retention` 작업이 부른다) */
    @SkeletonScheduled(id = "inquiry.retention", localTime = "04:50", lockKey = "inquiry.retention")
    fun scheduled() {
        purge()
    }
}
