package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import org.slf4j.LoggerFactory

data class ReviewPageView(val values: List<ReviewLine>, val total: Long)

data class ReviewCursorView(val values: List<ReviewLine>, val nextCursor: String?, val hasNext: Boolean)

data class TargetRating(val target: ReviewTarget, val summary: RatingSummary)

/**
 * 리뷰 읽기 - 요약 · 일괄 요약 · 목록 · 내 리뷰, 그리고 앱 API [ReviewStats] · [Reviews].
 * 남에게는 `PUBLISHED` 줄만 읽는다(저장소 질의가 그 줄만 가져온다 - 응답에서 걸러 내는 것이 아니다). 작성자 이름은 [AuthorDirectory] 를 **요청 하나에 한 번** 부른다.
 */
class ReviewReader(
    private val store: ReviewStore,
    private val properties: ReviewProperties,
    private val targets: ReviewTargetRules,
    private val policy: () -> ReviewPolicy,
    private val directory: () -> AuthorDirectory,
) : ReviewStats, Reviews {
    private val log = LoggerFactory.getLogger(ReviewReader::class.java)

    fun summary(viewer: String?, target: ReviewTarget): TargetSummary {
        targets.requireShape(target)
        val stats = store.stats(target)
        if (viewer == null) return TargetSummary(stats, null, false, WriteBlockedReason.SIGN_IN)
        val mine = store.findByTarget(target, viewer)
        val live = mine?.takeIf { it.status != ReviewStatus.DELETED }
        val blocked = when {
            live != null -> WriteBlockedReason.ALREADY_WRITTEN
            mine?.moderatedBy != null -> WriteBlockedReason.LOCKED
            properties.requirePurchase && !store.hasLivePurchase(viewer, target) -> WriteBlockedReason.PURCHASE_REQUIRED
            else -> null
        }
        val canWrite = blocked == null && policy().canWrite(viewer, target)
        return TargetSummary(stats, live?.id, canWrite, blocked)
    }

    /** 대상별 평균 - 요청한 순서 그대로 */
    fun summaries(requested: List<ReviewTarget>): List<TargetRating> {
        requested.forEach(targets::requireShape)
        val stats = store.statsOf(requested.distinct())
        return requested.map { TargetRating(it, stats.getValue(it).toRating()) }
    }

    fun list(viewer: String?, target: ReviewTarget, filter: ReviewFilter, sort: ReviewSort, offset: Int, limit: Int): ReviewPageView {
        targets.requireShape(target)
        val rows = store.page(target, viewer, filter, sort, offset, limit)
        return ReviewPageView(lines(viewer, rows.rows), rows.total)
    }

    /** 내 리뷰 - 숨겨진 것도 사유와 함께 읽는다. 지운 것은 없다 */
    fun mine(accountId: String, cursor: String?, size: Int): ReviewCursorView {
        val rows = store.pageMine(accountId, cursor, size + 1)
        val page = rows.take(size)
        val hasNext = rows.size > size
        return ReviewCursorView(lines(accountId, page), if (hasNext) page.lastOrNull()?.id else null, hasNext)
    }

    /** 한 줄을 보는 사람 기준으로 - 방금 쓰거나 고친 내 줄을 응답 모양으로 만들 때 */
    fun one(viewer: String, id: String): ReviewLine? = store.find(id)?.let { lines(viewer, listOf(it)).single() }

    override fun of(targets: Collection<ReviewTarget>): Map<ReviewTarget, RatingSummary> =
        store.statsOf(targets.distinct()).mapValues { it.value.toRating() }

    override fun countWritten(accountId: String): Int = store.countWritten(accountId)

    private fun lines(viewer: String?, rows: List<Review>): List<ReviewLine> {
        if (rows.isEmpty()) return emptyList()
        val helpful = viewer?.let { store.helpfulOf(it, rows.map(Review::id)) }.orEmpty()
        val names = rows.groupBy { it.targetType }.mapValues { (type, group) -> namesOf(type, group.map { it.accountId }.filterNot(::isTombstone).distinct()) }
        return rows.map { r ->
            val card = names[r.targetType]?.get(r.accountId)
            ReviewLine(r, helpful = r.id in helpful, mine = r.accountId == viewer, authorName = card?.name, authorTag = card?.tag, authorDeleted = isTombstone(r.accountId) || card?.deleted == true)
        }
    }

    /** 이름은 장식이지 계약이 아니다 - 고리가 느리거나 던져도 리뷰는 이름만 비운 채 나간다 */
    private fun namesOf(type: String, accountIds: List<String>): Map<String, AuthorCard> {
        if (accountIds.isEmpty()) return emptyMap()
        return try {
            directory().resolve(accountIds, AuthorContext(AUTHOR_SOURCE, type))
        } catch (e: Exception) {
            log.warn("review author names unavailable ({} authors): {}", accountIds.size, e.toString())
            emptyMap()
        }
    }

    private fun isTombstone(accountId: String) = accountId.startsWith(DELETED_PREFIX)

    private fun TargetStats.toRating() = RatingSummary(count, average)

    companion object {
        /** [AuthorContext.source] 에 실리는 이 모듈의 이름 */
        const val AUTHOR_SOURCE = "review"
        private const val DELETED_PREFIX = "deleted:"
    }
}
