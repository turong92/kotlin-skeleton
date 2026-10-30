package dev.sumin.skeleton.review.web

import com.fasterxml.jackson.annotation.JsonInclude
import dev.sumin.skeleton.review.RatingSummary
import dev.sumin.skeleton.review.ReviewLine
import dev.sumin.skeleton.review.ReviewStatus
import dev.sumin.skeleton.review.TargetRating
import dev.sumin.skeleton.review.TargetSummary
import java.time.Instant

/** 신고 사유 코드 - 문구는 프론트가 고른다 */
val REPORT_REASONS = listOf("SPAM", "ABUSE", "PRIVACY", "OFF_TOPIC", "OTHER")

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ConfigResponse(
    val targetTypes: List<String>,
    val bodyMinLength: Int,
    val bodyMaxLength: Int,
    val maxPhotos: Int,
    val reportReasons: List<String>,
    val canModerate: Boolean,
    val canReply: Boolean,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class SummaryResponse(
    val count: Int,
    val average: Double?,
    val distribution: Map<Int, Int>,
    val myReviewId: String?,
    val canWrite: Boolean,
    val writeBlockedReason: String?,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class TargetRatingResponse(val targetType: String, val targetId: String, val count: Int, val average: Double?)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PhotoResponse(val index: Int)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReplyResponse(val body: String, val createdAt: Instant, val updatedAt: Instant)

/**
 * 리뷰 한 줄. **계정 id 는 어떤 칸에도 없다.** `status` · `moderationReason` 은 본인에게만 실린다(남에게는 `PUBLISHED` 줄만 가므로 둘 다 필요 없다).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
open class ReviewResponse(
    val id: String,
    val rating: Int,
    val body: String,
    val verified: Boolean,
    val helpfulCount: Int,
    val helpful: Boolean,
    val mine: Boolean,
    val authorName: String?,
    val authorTag: String?,
    val authorDeleted: Boolean,
    val edited: Boolean,
    val photos: List<PhotoResponse>,
    val reply: ReplyResponse?,
    val createdAt: Instant,
    val updatedAt: Instant,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val status: ReviewStatus?,
    @field:JsonInclude(JsonInclude.Include.NON_NULL) val moderationReason: String?,
)

/** `GET /me` 의 한 줄 - 어느 대상의 리뷰인지가 더 실린다 */
class MyReviewResponse(base: ReviewResponse, val targetType: String, val targetId: String) :
    ReviewResponse(
        base.id, base.rating, base.body, base.verified, base.helpfulCount, base.helpful, base.mine, base.authorName, base.authorTag, base.authorDeleted,
        base.edited, base.photos, base.reply, base.createdAt, base.updatedAt, base.status, base.moderationReason,
    )

@JsonInclude(JsonInclude.Include.ALWAYS)
data class HelpfulResponse(val helpfulCount: Int, val helpful: Boolean)

fun ReviewLine.toResponse(): ReviewResponse {
    val r = review
    return ReviewResponse(
        id = r.id, rating = r.rating, body = r.body, verified = r.verified, helpfulCount = r.helpfulCount, helpful = helpful, mine = mine,
        authorName = authorName, authorTag = authorTag, authorDeleted = authorDeleted, edited = r.updatedAt.isAfter(r.publishedAt),
        photos = (0 until r.photoCount).map { PhotoResponse(it) }, reply = null, createdAt = r.publishedAt, updatedAt = r.updatedAt,
        status = r.status.takeIf { mine }, moderationReason = r.moderationReason.takeIf { mine },
    )
}

fun ReviewLine.toMyResponse() = MyReviewResponse(toResponse(), review.targetType, review.targetId)

fun TargetSummary.toResponse() = SummaryResponse(stats.count, stats.average, stats.distribution, myReviewId, canWrite, writeBlockedReason?.name)

fun TargetRating.toResponse(): TargetRatingResponse = summary.let { s: RatingSummary -> TargetRatingResponse(target.type, target.id, s.count, s.average) }
