package dev.sumin.skeleton.payment.iap

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

/** 구매 확인의 오류 코드 - 앱은 코드로 「끝낼지 / 다시 보낼지」를 가른다(스펙 §3 표). */
enum class IapErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    /** 스토어가 모르는 구매(위조 · 다른 앱) - 앱은 끝낸다. */
    PURCHASE_NOT_FOUND("PAYMENT.IAP_PURCHASE_NOT_FOUND", HttpStatus.NOT_FOUND, "The store does not know this purchase"),

    /** 다른 계정의 구매 - 앱은 **끝내지 않는다**(원래 계정으로 로그인하면 다시 보내진다). */
    PURCHASE_OWNED_ELSEWHERE("PAYMENT.IAP_PURCHASE_OWNED_ELSEWHERE", HttpStatus.CONFLICT, "This purchase belongs to another account"),

    /** 상품 지도에 없는 상품 - 지급하지 않는다. */
    PRODUCT_UNKNOWN("PAYMENT.IAP_PRODUCT_UNKNOWN", HttpStatus.UNPROCESSABLE_ENTITY, "This product is not sold here"),

    /** 환불 · 취소 · 끝난 구독 - 지급하지 않는다. */
    PURCHASE_REVOKED("PAYMENT.IAP_PURCHASE_REVOKED", HttpStatus.UNPROCESSABLE_ENTITY, "This purchase is no longer in effect"),

    /** 가족 공유 · 수량 2 이상 - 지급하지 않는다. */
    PURCHASE_NOT_ELIGIBLE("PAYMENT.IAP_PURCHASE_NOT_ELIGIBLE", HttpStatus.UNPROCESSABLE_ENTITY, "This purchase cannot be granted"),
}

/** 응답의 `detail` 은 고정 문장이다 - 스토어의 거래 번호 · 구매 토큰을 싣지 않는다. */
class IapPurchaseNotFoundException : ApplicationException("The store does not know this purchase.", IapErrorCode.PURCHASE_NOT_FOUND)

class IapPurchaseOwnedElsewhereException : ApplicationException("This purchase belongs to another account.", IapErrorCode.PURCHASE_OWNED_ELSEWHERE)

class IapProductUnknownException : ApplicationException("This product is not sold here.", IapErrorCode.PRODUCT_UNKNOWN)

class IapPurchaseRevokedException : ApplicationException("This purchase is no longer in effect.", IapErrorCode.PURCHASE_REVOKED)

class IapPurchaseNotEligibleException : ApplicationException("This purchase cannot be granted (family sharing and quantities above one are not supported).", IapErrorCode.PURCHASE_NOT_ELIGIBLE)
