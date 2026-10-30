package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 운영 처리 - 숨기기 · 되살리기 · 신고 확인(문제없음) · 운영자 답글. 권한 판정은 경로(컨트롤러)가 하고 여기는 규칙만 지킨다.
 * 잠금 순서는 쓰기와 같다(리뷰 줄 → 집계 줄). 숨김 · 되살림은 집계를 같이 바꾸고 이벤트 고리도 같은 트랜잭션에서 부른다. 알림은 커밋 뒤에 보내고 실패해도 운영 처리는 남는다.
 */
class ReviewModerationService(
    private val store: ReviewStore,
    private val content: ReviewContentRules,
    private val listeners: () -> List<ReviewEventListener>,
    private val notifier: () -> ReviewNotifier,
    private val time: TimeProvider,
) {
    /** 숨긴다. 이미 숨겼으면 그대로. 지운 줄은 404 */
    fun hide(operatorId: String, id: String, reason: String?) {
        val text = content.moderationReason(reason)
        store.inTransaction {
            val review = store.lock(id)?.takeIf { it.status != ReviewStatus.DELETED } ?: throw notFound()
            hideLocked(review, operatorId, text)
        }
    }

    /** 신고가 쌓여 자동으로 숨긴다 - 호출자가 줄을 잠그고 있다 */
    internal fun hideLocked(review: Review, by: String, reason: String) {
        if (review.status != ReviewStatus.PUBLISHED) return
        val now = time.now()
        store.hide(review.id, by, reason, now)
        store.adjustStats(review.target, review.rating, -1, now)
        listeners().forEach { it.onRemoved(review) }
        AfterCommit.run { notifier().hidden(review.accountId, review, reason) }
    }

    /** 되살린다 - 숨긴 줄, 또는 운영자가 숨긴 뒤 작성자가 지워 잠긴 줄(되살리기는 운영자만 풀 수 있다). 이미 공개면 그대로 */
    fun restore(id: String) {
        store.inTransaction {
            val review = store.lock(id) ?: throw notFound()
            val restorable = review.status == ReviewStatus.HIDDEN || (review.status == ReviewStatus.DELETED && review.moderatedBy != null)
            if (review.status == ReviewStatus.PUBLISHED) return@inTransaction
            if (!restorable) throw notFound()
            val now = time.now()
            store.restore(id, now)
            store.adjustStats(review.target, review.rating, +1, now)
            listeners().forEach { it.onPublished(checkNotNull(store.find(id)), false) }
        }
    }

    /** 신고를 확인한 것으로 친다(문제없음) - 새 신고가 들어오면 큐에 다시 오른다 */
    fun dismissReports(id: String) {
        store.inTransaction {
            store.lock(id)?.takeIf { it.status != ReviewStatus.DELETED } ?: throw notFound()
            store.dismissReports(id, time.now())
        }
    }

    /** 답글을 달거나 고친다(리뷰당 하나) - 공개된 리뷰에만 */
    fun reply(operatorId: String, id: String, body: String?) {
        val text = content.replyBody(body)
        store.inTransaction {
            val review = store.lock(id)?.takeIf { it.status == ReviewStatus.PUBLISHED } ?: throw notFound()
            store.saveReply(id, text, operatorId, time.now())
            AfterCommit.run { notifier().replied(review.accountId, review) }
        }
    }

    fun removeReply(id: String) {
        store.inTransaction {
            store.lock(id) ?: throw notFound()
            store.deleteReply(id)
        }
    }

    private fun notFound() = ReviewException(ReviewErrorCode.NOT_FOUND, "Review not found")
}
