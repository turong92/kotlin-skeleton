package dev.sumin.skeleton.review

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** 리뷰를 다는 대상 — 종류(`product` …)와 그 종류 안의 id. 어떤 종류가 열려 있는지는 `skeleton.review.target-types` 가 정한다 */
data class ReviewTarget(val type: String, val id: String)

/** 리뷰 줄의 상태. 줄은 지워지지 않는다 — `DELETED` 로 바뀔 뿐이고, 같은 대상에 다시 쓰면 같은 줄이 `PUBLISHED` 로 되살아난다 */
enum class ReviewStatus { PUBLISHED, HIDDEN, DELETED }

/** 리뷰 한 줄(저장 모양). [accountId] 는 서버 안에서만 쓴다 - 어떤 응답에도 나가지 않는다 */
data class Review(
    val id: String,
    val targetType: String,
    val targetId: String,
    val accountId: String,
    val rating: Int,
    val body: String,
    val status: ReviewStatus,
    val verified: Boolean,
    val helpfulCount: Int,
    val reportCount: Int,
    /** 운영자가 숨긴 적이 있으면 그 운영자(아니면 null) - 숨긴 뒤 지운 줄은 작성자가 다시 쓰지 못한다 */
    val moderatedBy: String?,
    val moderationReason: String?,
    val photoCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 이 내용이 공개된 시각 - 목록 정렬 기준이고 [updatedAt] 이 이보다 늦으면 「수정됨」이다 */
    val publishedAt: Instant,
) {
    val target: ReviewTarget get() = ReviewTarget(targetType, targetId)
}

/** 대상 하나의 집계 - `PUBLISHED` 줄만 센다 */
data class TargetStats(val count: Int, val ratingSum: Int, val distribution: Map<Int, Int>) {
    /** 단순 평균, 소수 한 자리(반올림). 리뷰가 없으면 null */
    val average: Double?
        get() = if (count == 0) null else BigDecimal(ratingSum).divide(BigDecimal(count), 1, RoundingMode.HALF_UP).toDouble()

    companion object {
        val EMPTY = TargetStats(0, 0, (1..5).associateWith { 0 })
    }
}

/** 구매 신호 한 줄 - [ref] 가 같으면 한 줄이다 */
data class ReviewPurchase(
    val id: String,
    val accountId: String,
    val targetType: String,
    val targetId: String,
    val ref: String,
    val createdAt: Instant,
    val revokedAt: Instant?,
) {
    val target: ReviewTarget get() = ReviewTarget(targetType, targetId)
}

enum class ReviewSort(val param: String) {
    RECENT("recent"),
    HELPFUL("helpful"),
    RATING_HIGH("rating_high"),
    RATING_LOW("rating_low");

    companion object {
        fun of(param: String?): ReviewSort? = if (param == null) RECENT else entries.firstOrNull { it.param == param }
    }
}

/** 목록 거르기. 모두 기본값이면 걸러 내지 않는다 */
data class ReviewFilter(val rating: Int? = null, val verified: Boolean = false, val photo: Boolean = false)

/** 목록 한 쪽의 줄과 (걸러진) 전체 개수 */
data class ReviewRows(val rows: List<Review>, val total: Long)

/** 화면에 내보낼 리뷰 한 줄 - 저장 줄에 보는 사람 기준 값(도움돼요를 눌렀나 · 내 것인가)과 작성자 이름을 얹었다 */
data class ReviewLine(
    val review: Review,
    val helpful: Boolean,
    val mine: Boolean,
    val authorName: String?,
    val authorTag: String?,
    val authorDeleted: Boolean,
)

/** [ReviewStats.of] 가 돌려주는 대상별 평균 - 앱이 자기 목록 응답에 끼운다 */
data class RatingSummary(val count: Int, val average: Double?)

/** 리뷰를 쓸 수 없는 까닭 - 프론트가 문구를 고르는 코드 */
enum class WriteBlockedReason { PURCHASE_REQUIRED, ALREADY_WRITTEN, LOCKED, SIGN_IN }

data class TargetSummary(
    val stats: TargetStats,
    val myReviewId: String?,
    val canWrite: Boolean,
    val writeBlockedReason: WriteBlockedReason?,
)

/** 도움돼요 상태 - [helpfulCount] 는 서버가 센 값 */
data class HelpfulState(val helpfulCount: Int, val helpful: Boolean)
