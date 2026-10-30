package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider

/** 쓰기 입력. 빠진 칸은 null - 만들 때는 [rating] 이 필수고, 고칠 때는 null 인 칸을 바꾸지 않는다 */
data class ReviewInput(val rating: Int? = null, val body: String? = null, val photos: List<String>? = null)

typealias ReviewPatch = ReviewInput

/**
 * 리뷰 쓰기 · 고치기 · 지우기와 도움돼요. 모든 쓰기는 한 트랜잭션이고 **이벤트 리스너도 그 안에서 불린다** - 던지면 쓰기도 되돌아간다.
 * 잠금 순서는 리뷰 줄 → 집계 줄 하나다. 새 줄은 넣기를 먼저 하고(없는 키를 잠그지 않는다) 못 넣었을 때만 잠가 읽는다.
 */
class ReviewService(
    private val store: ReviewStore,
    private val properties: ReviewProperties,
    private val content: ReviewContentRules,
    private val targets: ReviewTargetRules,
    private val policy: () -> ReviewPolicy,
    private val listeners: () -> List<ReviewEventListener>,
    private val time: TimeProvider,
    private val ids: SortableIds = SortableIds.system,
) {
    fun create(accountId: String, target: ReviewTarget, input: ReviewInput): Review {
        targets.requireExists(target)
        val rating = content.rating(input.rating)
        val body = content.body(input.body)
        content.photos(input.photos)
        if (!policy().canWrite(accountId, target)) throw ReviewException(ReviewErrorCode.FORBIDDEN, "Not allowed to review this")
        return store.inTransaction {
            store.findByTarget(target, accountId)?.let(::requireRewritable)
            val verified = store.hasLivePurchase(accountId, target)
            if (properties.requirePurchase && !verified) throw ReviewException(ReviewErrorCode.PURCHASE_REQUIRED, "Only buyers can review this")
            val now = time.now()
            val fresh = Review(
                id = ids.newId("rvw_"), targetType = target.type, targetId = target.id, accountId = accountId, rating = rating, body = body,
                status = ReviewStatus.PUBLISHED, verified = verified, helpfulCount = 0, reportCount = 0, moderatedBy = null, moderationReason = null,
                photoCount = 0, createdAt = now, updatedAt = now, publishedAt = now,
            )
            if (store.insertIfAbsent(fresh)) {
                store.adjustStats(target, rating, +1, now)
                publish(fresh, firstTime = true)
                return@inTransaction fresh
            }
            // 동시에 온 다른 쓰기가 먼저 넣었다 - 잠가 읽고 되살릴 수 있는 줄인지 본다
            val existing = store.lockByTarget(target, accountId) ?: throw ReviewException(ReviewErrorCode.ALREADY_WRITTEN, "Already reviewed")
            requireRewritable(existing)
            store.revive(existing.id, rating, body, verified, now)
            store.adjustStats(target, rating, +1, now)
            val revived = checkNotNull(store.find(existing.id))
            publish(revived, firstTime = false)
            revived
        }
    }

    fun update(accountId: String, id: String, patch: ReviewPatch): Review = store.inTransaction {
        val current = ownedLive(accountId, id)
        if (current.status != ReviewStatus.PUBLISHED) throw ReviewException(ReviewErrorCode.FORBIDDEN, "A hidden review can only be deleted")
        val rating = patch.rating?.let { content.rating(it) } ?: current.rating
        val body = patch.body?.let { content.body(it) } ?: current.body
        content.photos(patch.photos)
        val now = time.now()
        store.updateContent(id, rating, body, now)
        if (rating != current.rating) {
            store.adjustStats(current.target, current.rating, -1, now)
            store.adjustStats(current.target, rating, +1, now)
        }
        checkNotNull(store.find(id))
    }

    fun remove(accountId: String, id: String) {
        store.inTransaction {
            val current = ownedLive(accountId, id)
            val now = time.now()
            store.markDeleted(id, now)
            if (current.status == ReviewStatus.PUBLISHED) {
                store.adjustStats(current.target, current.rating, -1, now)
                listeners().forEach { it.onRemoved(current) }
            }
        }
    }

    /** 도움돼요를 누르거나(true) 뗀다. 이미 그 상태면 그대로다. 자기 리뷰 403 · 숨김 · 지움 404 */
    fun setHelpful(accountId: String, id: String, helpful: Boolean): HelpfulState = store.inTransaction {
        val review = store.lock(id)?.takeIf { it.status == ReviewStatus.PUBLISHED } ?: throw notFound()
        if (review.accountId == accountId) throw ReviewException(ReviewErrorCode.OWN_REVIEW, "You cannot mark your own review")
        var count = review.helpfulCount
        if (helpful && !store.hasHelpful(id, accountId)) {
            store.insertHelpful(id, accountId, time.now())
            count = store.adjustHelpful(id, +1)
        } else if (!helpful && store.deleteHelpful(id, accountId)) {
            count = store.adjustHelpful(id, -1)
        }
        HelpfulState(count, store.hasHelpful(id, accountId))
    }

    /** 작성자 본인의 살아 있는(`DELETED` 아닌) 줄을 잠가 읽는다. 남의 줄은 공개 상태일 때만 403, 아니면 없는 것과 같은 404 */
    private fun ownedLive(accountId: String, id: String): Review {
        val review = store.lock(id)?.takeIf { it.status != ReviewStatus.DELETED } ?: throw notFound()
        if (review.accountId != accountId) {
            throw if (review.status == ReviewStatus.PUBLISHED) ReviewException(ReviewErrorCode.FORBIDDEN, "Not your review") else notFound()
        }
        return review
    }

    /** 이 줄 위에 다시 쓸 수 있나 - 살아 있으면 409, 운영자가 숨긴 뒤 지운 줄이면 잠김 409 */
    private fun requireRewritable(existing: Review) {
        when {
            existing.status != ReviewStatus.DELETED -> throw ReviewException(ReviewErrorCode.ALREADY_WRITTEN, "Already reviewed")
            existing.moderatedBy != null -> throw ReviewException(ReviewErrorCode.LOCKED, "Hidden by a moderator, cannot be rewritten")
        }
    }

    private fun publish(review: Review, firstTime: Boolean) = listeners().forEach { it.onPublished(review, firstTime) }

    private fun notFound() = ReviewException(ReviewErrorCode.NOT_FOUND, "Review not found")
}
