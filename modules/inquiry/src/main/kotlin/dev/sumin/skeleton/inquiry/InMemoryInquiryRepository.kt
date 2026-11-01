package dev.sumin.skeleton.inquiry

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 프로세스 안에서만 남는 기본 저장소 — 시험 · DB 없는 조립용 */
class InMemoryInquiryRepository : InquiryRepository {
    private val rows = ConcurrentHashMap<Long, Inquiry>()
    private val seq = AtomicLong()

    override fun create(inquiry: NewInquiry): Inquiry {
        val saved = Inquiry(
            seq.incrementAndGet(), inquiry.kind, inquiry.body, inquiry.replyEmail, inquiry.accountId,
            inquiry.context, inquiry.lang, InquiryStatus.NEW, inquiry.at, null,
        )
        rows[saved.id] = saved
        return saved
    }

    override fun find(id: Long): Inquiry? = rows[id]

    override fun list(status: InquiryStatus?, limit: Int): List<Inquiry> =
        rows.values.filter { status == null || it.status == status }.sortedByDescending { it.id }.take(limit)

    override fun setStatus(id: Long, status: InquiryStatus, at: Instant): Inquiry? =
        rows.computeIfPresent(id) { _, row -> row.copy(status = status, handledAt = if (status == InquiryStatus.DONE) at else null) }

    override fun detachAccount(accountId: String): Int {
        var count = 0
        rows.replaceAll { _, row -> if (row.accountId == accountId) row.copy(accountId = null).also { count++ } else row }
        return count
    }
}
