package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 쿠폰 에러 코드 — `COUPON.` 으로 시작한다 (프론트는 이 문자열로 분기한다) */
enum class CouponErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    DEFINITION_INVALID("COUPON.DEFINITION_INVALID", HttpStatus.BAD_REQUEST, "Coupon definition is invalid"),
    DEFINITION_NOT_FOUND("COUPON.DEFINITION_NOT_FOUND", HttpStatus.NOT_FOUND, "Coupon definition not found"),
    ISSUE_CLOSED("COUPON.ISSUE_CLOSED", HttpStatus.CONFLICT, "Coupon issuing is closed for now"),
    DEFINITION_NOT_ACTIVE("COUPON.DEFINITION_NOT_ACTIVE", HttpStatus.CONFLICT, "Coupon definition is not issuing"),
    NOT_FOUND("COUPON.NOT_FOUND", HttpStatus.NOT_FOUND, "Coupon not found"),
    NOT_REVOCABLE("COUPON.NOT_REVOCABLE", HttpStatus.CONFLICT, "Only an available coupon can be revoked"),
    CODE_INVALID("COUPON.CODE_INVALID", HttpStatus.BAD_REQUEST, "Coupon code is not valid"),
    CODE_USED("COUPON.CODE_USED", HttpStatus.CONFLICT, "Coupon code was already used by this account"),
    CODE_EXHAUSTED("COUPON.CODE_EXHAUSTED", HttpStatus.CONFLICT, "Coupon code has no redemptions left"),
    CODE_EXISTS("COUPON.CODE_EXISTS", HttpStatus.CONFLICT, "Coupon code already exists"),
}

class CouponException(val couponError: CouponErrorCode, message: String) : ApplicationException(message, couponError)
