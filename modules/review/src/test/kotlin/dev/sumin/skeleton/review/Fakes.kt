package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** 메모리 저장소 - 서비스 규칙 시험용이다(동시성 · 트랜잭션 되돌림은 review-jdbc 의 dbTest 가 진짜 DB 로 본다). 모든 호출이 한 모니터를 잡는다 */
class FakeReviewStore : ReviewStore {
    private val reviews = LinkedHashMap<String, Review>()
    private val stats = HashMap<ReviewTarget, IntArray>()   // [count, sum, r1..r5]
    private val helpful = HashSet<Pair<String, String>>()
    private val purchases = LinkedHashMap<String, ReviewPurchase>()
    private val reports = LinkedHashMap<Pair<String, String>, ReportReason>()
    private val replies = HashMap<String, ReviewReply>()
    private val photos = HashMap<String, List<String>>()

    @Synchronized fun clear() {
        reviews.clear(); stats.clear(); helpful.clear(); purchases.clear(); reports.clear(); replies.clear(); photos.clear()
    }

    @Synchronized fun put(review: Review) { reviews[review.id] = review }

    @Synchronized fun all(): List<Review> = reviews.values.toList()

    override fun <T> inTransaction(block: () -> T): T = block()

    @Synchronized override fun find(id: String) = reviews[id]
    @Synchronized override fun findByTarget(target: ReviewTarget, accountId: String) =
        reviews.values.firstOrNull { it.target == target && it.accountId == accountId }
    override fun lock(id: String) = find(id)
    override fun lockByTarget(target: ReviewTarget, accountId: String) = findByTarget(target, accountId)

    @Synchronized override fun insertIfAbsent(review: Review): Boolean {
        if (findByTarget(review.target, review.accountId) != null) return false
        reviews[review.id] = review
        return true
    }

    @Synchronized override fun revive(id: String, rating: Int, body: String, verified: Boolean, at: Instant): Boolean {
        val r = reviews[id]?.takeIf { it.status == ReviewStatus.DELETED } ?: return false
        reviews[id] = r.copy(status = ReviewStatus.PUBLISHED, rating = rating, body = body, verified = verified, helpfulCount = 0, reportCount = 0, reportedAt = null, reportsReviewedAt = null, createdAt = at, updatedAt = at, publishedAt = at, photoCount = 0)
        helpful.removeAll { it.first == id }
        reports.keys.removeAll { it.first == id }; replies.remove(id); photos.remove(id)
        return true
    }

    @Synchronized override fun updateContent(id: String, rating: Int, body: String, at: Instant): Boolean {
        val r = reviews[id] ?: return false
        reviews[id] = r.copy(rating = rating, body = body, updatedAt = at)
        return true
    }

    @Synchronized override fun markDeleted(id: String, at: Instant): Boolean {
        val r = reviews[id]?.takeIf { it.status != ReviewStatus.DELETED } ?: return false
        reviews[id] = r.copy(status = ReviewStatus.DELETED, updatedAt = at)
        return true
    }

    @Synchronized override fun page(target: ReviewTarget, viewer: String?, filter: ReviewFilter, sort: ReviewSort, offset: Int, limit: Int): ReviewRows {
        val matching = reviews.values.filter { r ->
            r.target == target &&
                (r.status == ReviewStatus.PUBLISHED || (r.status == ReviewStatus.HIDDEN && viewer != null && r.accountId == viewer)) &&
                (filter.rating == null || r.rating == filter.rating) && (!filter.verified || r.verified) && (!filter.photo || r.photoCount > 0)
        }
        val ordered = when (sort) {
            ReviewSort.RECENT -> matching.sortedWith(compareByDescending<Review> { it.publishedAt }.thenByDescending { it.id })
            ReviewSort.HELPFUL -> matching.sortedWith(compareByDescending<Review> { it.helpfulCount }.thenByDescending { it.publishedAt }.thenByDescending { it.id })
            ReviewSort.RATING_HIGH -> matching.sortedWith(compareByDescending<Review> { it.rating }.thenByDescending { it.publishedAt }.thenByDescending { it.id })
            ReviewSort.RATING_LOW -> matching.sortedWith(compareBy<Review> { it.rating }.thenByDescending { it.publishedAt }.thenByDescending { it.id })
        }
        return ReviewRows(ordered.drop(offset).take(limit), matching.size.toLong())
    }

    @Synchronized override fun hasReport(reviewId: String, accountId: String) = (reviewId to accountId) in reports
    @Synchronized override fun insertReport(reviewId: String, accountId: String, reason: ReportReason, detail: String?, at: Instant) { reports[reviewId to accountId] = reason }
    @Synchronized override fun addReportCount(reviewId: String, at: Instant): Int {
        val r = reviews.getValue(reviewId).let { it.copy(reportCount = it.reportCount + 1, reportedAt = at) }
        reviews[reviewId] = r
        return r.reportCount
    }
    @Synchronized override fun hide(id: String, by: String, reason: String, at: Instant): Boolean {
        val r = reviews[id]?.takeIf { it.status == ReviewStatus.PUBLISHED } ?: return false
        reviews[id] = r.copy(status = ReviewStatus.HIDDEN, moderatedBy = by, moderationReason = reason, reportsReviewedAt = at, updatedAt = at)
        return true
    }
    @Synchronized override fun restore(id: String, at: Instant): Boolean {
        val r = reviews[id]?.takeIf { it.status == ReviewStatus.HIDDEN || (it.status == ReviewStatus.DELETED && it.moderatedBy != null) } ?: return false
        reviews[id] = r.copy(status = ReviewStatus.PUBLISHED, moderatedBy = null, moderationReason = null, updatedAt = at)
        return true
    }
    @Synchronized override fun dismissReports(id: String, at: Instant): Boolean {
        val r = reviews[id] ?: return false
        reviews[id] = r.copy(reportsReviewedAt = at)
        return true
    }
    @Synchronized override fun pageAdmin(queue: ReviewQueue, targetType: String?, before: String?, limit: Int) =
        reviews.values.filter { r ->
            (targetType == null || r.targetType == targetType) && (before == null || r.id < before) && when (queue) {
                ReviewQueue.REPORTED -> r.status == ReviewStatus.PUBLISHED && r.hasOpenReports
                ReviewQueue.HIDDEN -> r.status == ReviewStatus.HIDDEN
                ReviewQueue.ALL -> r.status != ReviewStatus.DELETED
            }
        }.sortedByDescending { it.id }.take(limit)
    @Synchronized override fun reportReasonCounts(reviewIds: Collection<String>) =
        reviewIds.associateWith { id -> reports.filterKeys { it.first == id }.values.groupingBy { it.name }.eachCount() }.filterValues { it.isNotEmpty() }

    @Synchronized override fun repliesOf(reviewIds: Collection<String>) = reviewIds.mapNotNull { id -> replies[id]?.let { id to it } }.toMap()
    @Synchronized override fun saveReply(reviewId: String, body: String, actorId: String, at: Instant) {
        replies[reviewId] = ReviewReply(body, actorId, replies[reviewId]?.createdAt ?: at, at)
    }
    @Synchronized override fun deleteReply(reviewId: String) = replies.remove(reviewId) != null

    @Synchronized override fun replacePhotos(reviewId: String, keys: List<String>) {
        photos[reviewId] = keys
        reviews[reviewId] = reviews.getValue(reviewId).copy(photoCount = keys.size)
    }
    @Synchronized override fun photoKey(reviewId: String, index: Int) = photos[reviewId]?.getOrNull(index)

    @Synchronized override fun pageMine(accountId: String, before: String?, limit: Int) =
        reviews.values.filter { it.accountId == accountId && it.status != ReviewStatus.DELETED && (before == null || it.id < before) }.sortedByDescending { it.id }.take(limit)

    @Synchronized override fun countWritten(accountId: String) = reviews.values.count { it.accountId == accountId }

    @Synchronized override fun stats(target: ReviewTarget): TargetStats {
        val s = stats[target] ?: return TargetStats.EMPTY
        return TargetStats(s[0], s[1], (1..5).associateWith { s[1 + it] })
    }
    @Synchronized override fun statsOf(targets: Collection<ReviewTarget>) = targets.associateWith { stats(it) }

    @Synchronized override fun adjustStats(target: ReviewTarget, rating: Int, delta: Int, at: Instant) {
        val s = stats.getOrPut(target) { IntArray(7) }
        s[0] += delta; s[1] += delta * rating; s[1 + rating] += delta
    }

    @Synchronized override fun hasHelpful(reviewId: String, accountId: String) = (reviewId to accountId) in helpful
    @Synchronized override fun helpfulOf(accountId: String, reviewIds: Collection<String>) = reviewIds.filter { (it to accountId) in helpful }.toSet()
    @Synchronized override fun insertHelpful(reviewId: String, accountId: String, at: Instant) { helpful += reviewId to accountId }
    @Synchronized override fun deleteHelpful(reviewId: String, accountId: String) = helpful.remove(reviewId to accountId)
    @Synchronized override fun adjustHelpful(reviewId: String, delta: Int): Int {
        val r = reviews.getValue(reviewId).let { it.copy(helpfulCount = it.helpfulCount + delta) }
        reviews[reviewId] = r
        return r.helpfulCount
    }

    @Synchronized override fun hasLivePurchase(accountId: String, target: ReviewTarget) =
        purchases.values.any { it.accountId == accountId && it.target == target && it.revokedAt == null }
    @Synchronized override fun lockPurchase(ref: String) = purchases[ref]
    @Synchronized override fun insertPurchaseIfAbsent(purchase: ReviewPurchase): Boolean {
        if (purchase.ref in purchases) return false
        purchases[purchase.ref] = purchase
        return true
    }
    @Synchronized override fun revokePurchase(ref: String, at: Instant): Boolean {
        val p = purchases[ref]?.takeIf { it.revokedAt == null } ?: return false
        purchases[ref] = p.copy(revokedAt = at)
        return true
    }
    @Synchronized override fun setVerified(accountId: String, target: ReviewTarget, verified: Boolean): Int {
        var n = 0
        reviews.values.filter { it.accountId == accountId && it.target == target }.forEach { reviews[it.id] = it.copy(verified = verified); n++ }
        return n
    }

    @Synchronized override fun eraseAccount(accountId: String, tombstone: String): Int {
        val mine = reviews.values.filter { it.accountId == accountId }
        mine.forEach { reviews[it.id] = it.copy(accountId = tombstone) }
        helpful.filter { it.second == accountId }.forEach { helpful.remove(it); helpful += it.first to tombstone }
        purchases.values.removeAll { it.accountId == accountId }
        return mine.size
    }
}

/** 바뀌는 시계 - 「수정됨」(updatedAt > publishedAt)을 보려면 시간이 가야 한다 */
class TickingTime(start: Instant = Instant.parse("2026-10-30T00:00:00Z")) : TimeProvider {
    private val now = java.util.concurrent.atomic.AtomicReference(start)
    override fun now(): Instant = now.get()
    fun advance(seconds: Long = 60) { now.updateAndGet { it.plusSeconds(seconds) } }
}

class RecordingListener : ReviewEventListener {
    val published = ConcurrentHashMap.newKeySet<Pair<String, Boolean>>()
    val removed = ConcurrentHashMap.newKeySet<String>()
    override fun onPublished(review: Review, firstTime: Boolean) { published += review.id to firstTime }
    override fun onRemoved(review: Review) { removed += review.id }
}

class RecordingNotifier : ReviewNotifier {
    val hidden = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, String>>()
    val replied = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    override fun hidden(accountId: String, review: Review, reason: String) { hidden += Triple(accountId, review.id, reason) }
    override fun replied(accountId: String, review: Review) { replied += accountId to review.id }
}
