package dev.sumin.skeleton.review

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 리뷰 에러 코드 - `REVIEW.` 로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class ReviewErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    TARGET_NOT_FOUND("REVIEW.TARGET_NOT_FOUND", HttpStatus.NOT_FOUND, "Review target not found"),
    NOT_FOUND("REVIEW.NOT_FOUND", HttpStatus.NOT_FOUND, "Review not found"),
    PURCHASE_REQUIRED("REVIEW.PURCHASE_REQUIRED", HttpStatus.FORBIDDEN, "Only buyers can review this"),
    OWN_REVIEW("REVIEW.OWN_REVIEW", HttpStatus.FORBIDDEN, "You cannot do this to your own review"),
    FORBIDDEN("REVIEW.FORBIDDEN", HttpStatus.FORBIDDEN, "Not allowed"),
    ALREADY_WRITTEN("REVIEW.ALREADY_WRITTEN", HttpStatus.CONFLICT, "You already reviewed this"),
    LOCKED("REVIEW.LOCKED", HttpStatus.CONFLICT, "This review was hidden by a moderator and cannot be rewritten"),
    CONTENT_INVALID("REVIEW.CONTENT_INVALID", HttpStatus.BAD_REQUEST, "Invalid review content"),
}

class ReviewException(val reviewError: ReviewErrorCode, message: String) : ApplicationException(message, reviewError)
