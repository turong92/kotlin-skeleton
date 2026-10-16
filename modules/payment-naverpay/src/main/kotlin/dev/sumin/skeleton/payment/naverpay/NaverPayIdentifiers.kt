package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/**
 * 결제 번호(`paymentId`) · 주문 번호(`merchantPayKey`)의 모양을 결제사를 부르기 **전에** 확인한다. 결제 번호는 구매자 브라우저가 가져온 값이고 조회 주소의 경로에 들어간다.
 * 문서의 결제 번호는 최대 50바이트(`20170201NP1043587746`)이고 문자 집합은 밝히지 않았다 - 영문 · 숫자 · `-` · `_` 로 넓게 받는다(**못 잰 가정**, `docs/real-provider-setup.md`).
 * 주문 번호는 문서상 최대 64바이트다.
 */
internal object NaverPayIdentifiers {
    private val PAYMENT_ID = Regex("[A-Za-z0-9_-]{1,50}")
    private val ORDER_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9_-]{1,64}")

    private val RESERVE_ID = Regex("[A-Za-z0-9_-]{1,50}")

    fun isReserveId(value: String): Boolean = RESERVE_ID.matches(value)

    fun isPaymentId(value: String): Boolean = PAYMENT_ID.matches(value)

    fun paymentId(value: String): String = if (isPaymentId(value)) value else throw NaverPayIdentifierRejectedException()

    fun orderId(value: String): String = if (ORDER_ID.matches(value)) value else throw NaverPayIdentifierRejectedException()

    /** 결제 예약은 부를 때마다 새 결제창이다 - 키도 새로 만든다. */
    fun reserveKey(): String = java.util.UUID.randomUUID().toString()

    /** 승인은 결제 번호마다 한 번이다 - 같은 결제 번호의 재요청은 같은 키라서 네이버페이가 409 로 처음 응답을 돌려준다(적용하지 않는다). 최대 64자. */
    fun applyKey(paymentId: String): String = "apply-$paymentId"

    /** 취소는 장부가 정한 환불 줄의 키를 쓴다(같은 환불의 재요청이 한 번으로 본다). 모양이 안 맞거나 없으면 새 키 - 재요청 보호만 없다. */
    fun cancelKey(supplied: String?): String = supplied?.takeIf { IDEMPOTENCY_KEY.matches(it) } ?: java.util.UUID.randomUUID().toString()
}

/** 결제 번호 · 주문 번호가 네이버페이의 모양이 아니어서 결제사를 부르지 않았다 - 호출자 잘못(400)이다. 문장은 고정이다(거절한 값을 싣지 않는다). */
class NaverPayIdentifierRejectedException : ApplicationException(
    message = "The payment identifier is not in a valid form.",
    errorCode = PlatformErrorCode.VALIDATION_FAILED,
)
