package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.ApplicationException

/** 남의 환불 · 없는 환불 — 둘을 구별하지 않는다(id 가 맞는지 알려 주지 않는다). */
class PaymentRefundNotFoundException : ApplicationException(
    message = "The payment refund was not found.",
    errorCode = PaymentErrorCode.REFUND_NOT_FOUND,
)

/** 지금 상태에서는 할 수 없는 환불 일(결제된 주문이 아니다 · 이미 결정된 요청이다 · 본인 요청이 꺼져 있다). */
class PaymentRefundNotAllowedException : ApplicationException(
    message = "The refund is not allowed in the current state.",
    errorCode = PaymentErrorCode.REFUND_NOT_ALLOWED,
)

/** 이 주문에 이미 열린 환불(요청됨 · 환불 중)이 있다 — 주문당 하나뿐이다. */
class PaymentRefundInProgressException : ApplicationException(
    message = "Another refund of this order is still open.",
    errorCode = PaymentErrorCode.REFUND_IN_PROGRESS,
)

/** 요청한 금액이 환불할 수 있는 금액(`총액 - 환불된 금액 - 열린 환불`)을 넘는다 — 결제사를 부르지 않았다. */
class PaymentRefundAmountExceededException : ApplicationException(
    message = "The refund amount exceeds the refundable amount.",
    errorCode = PaymentErrorCode.REFUND_AMOUNT_EXCEEDED,
)

/**
 * 결제사가 환불을 **확정 거절**했다 — 환불 줄은 `FAILED` 로 닫혔고 주문은 다시 환불할 수 있다. [category] 는 응답의 `data.category`, [providerCode] 는 `data.providerCode`(식별자 모양만).
 */
class PaymentRefundDeclinedException(
    val category: RefundDeclineCategory,
    val providerCode: String?,
) : ApplicationException(
    message = "The refund was declined.",
    errorCode = PaymentErrorCode.REFUND_DECLINED,
    data = mapOf("category" to category.name, "providerCode" to providerCode),
)

/**
 * 환불 전 확인(결제사 조회)이 실패해 **환불을 보내지 않았다** — "아무것도 나가지 않았다"는 확정이다(모름이 아니다: 환불 줄이 `PENDING` 으로 남지 않는다).
 * 본인의 요청 줄은 `REQUESTED` 그대로 두고 운영자가 다시 누를 수 있다. 응답은 503 이다.
 */
class PaymentRefundNotSentException : ApplicationException(
    message = "The refund was not sent because the payment provider could not be checked first.",
    errorCode = PaymentErrorCode.UNAVAILABLE,
)

/** 운영자가 닫을 수 없는 주문 — 구독 결제가 아니거나, 결제사의 답을 기다리는 중(`CONFIRMING`)이 아니거나, 결제사가 아직 처리 중이다. */
class PaymentOrderNotClosableException : ApplicationException(
    message = "The order cannot be closed in its current state.",
    errorCode = PaymentErrorCode.ORDER_NOT_CLOSABLE,
)
