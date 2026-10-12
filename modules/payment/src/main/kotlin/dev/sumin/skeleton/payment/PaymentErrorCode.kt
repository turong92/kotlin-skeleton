package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.ErrorCode
import org.springframework.http.HttpStatus

enum class PaymentErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
    override val defaultDetail: String? = null,
) : ErrorCode {
    PROVIDER_NOT_FOUND(
        code = "PAYMENT.PROVIDER_NOT_FOUND",
        status = HttpStatus.BAD_REQUEST,
        title = "Payment provider not found",
    ),
    ROUTING_FAILED(
        code = "PAYMENT.ROUTING_FAILED",
        status = HttpStatus.BAD_REQUEST,
        title = "Payment provider routing failed",
    ),
    PROVIDER_ERROR(
        code = "PAYMENT.PROVIDER_ERROR",
        status = HttpStatus.BAD_GATEWAY,
        title = "Payment provider error",
    ),
    CAPABILITY_UNSUPPORTED(
        code = "PAYMENT.CAPABILITY_UNSUPPORTED",
        status = HttpStatus.BAD_REQUEST,
        title = "Payment provider capability unsupported",
    ),
    WEBHOOK_REJECTED(
        code = "PAYMENT.WEBHOOK_REJECTED",
        status = HttpStatus.UNAUTHORIZED,
        title = "Payment webhook rejected",
    ),
    WEBHOOK_NOT_FOUND(
        code = "PAYMENT.WEBHOOK_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Payment webhook address not found",
    ),
    WEBHOOK_TOO_LARGE(
        code = "PAYMENT.WEBHOOK_TOO_LARGE",
        status = HttpStatus.CONTENT_TOO_LARGE,
        title = "Payment webhook body too large",
    ),
    WEBHOOK_EVENT_NOT_FOUND(
        code = "PAYMENT.WEBHOOK_EVENT_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Payment webhook event not found",
    ),
    ORDER_NOT_FOUND(
        code = "PAYMENT.ORDER_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Payment order not found",
    ),
    ITEM_NOT_PURCHASABLE(
        code = "PAYMENT.ITEM_NOT_PURCHASABLE",
        status = HttpStatus.UNPROCESSABLE_ENTITY,
        title = "Item not purchasable",
    ),
    IDEMPOTENCY_CONFLICT(
        code = "PAYMENT.IDEMPOTENCY_CONFLICT",
        status = HttpStatus.CONFLICT,
        title = "Idempotency key reused with a different request",
    ),
    AMOUNT_MISMATCH(
        code = "PAYMENT.AMOUNT_MISMATCH",
        status = HttpStatus.BAD_REQUEST,
        title = "Payment amount mismatch",
    ),
    ORDER_NOT_PAYABLE(
        code = "PAYMENT.ORDER_NOT_PAYABLE",
        status = HttpStatus.CONFLICT,
        title = "Payment order cannot be paid",
    ),
    MODE_MISMATCH(
        code = "PAYMENT.MODE_MISMATCH",
        status = HttpStatus.CONFLICT,
        title = "Payment mode mismatch",
    ),
    DECLINED(
        code = "PAYMENT.DECLINED",
        status = HttpStatus.PAYMENT_REQUIRED,
        title = "Payment declined",
    ),
    REFUND_NOT_FOUND(
        code = "PAYMENT.REFUND_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Payment refund not found",
    ),
    REFUND_NOT_ALLOWED(
        code = "PAYMENT.REFUND_NOT_ALLOWED",
        status = HttpStatus.CONFLICT,
        title = "Refund not allowed",
    ),
    REFUND_IN_PROGRESS(
        code = "PAYMENT.REFUND_IN_PROGRESS",
        status = HttpStatus.CONFLICT,
        title = "Another refund is open",
    ),
    REFUND_AMOUNT_EXCEEDED(
        code = "PAYMENT.REFUND_AMOUNT_EXCEEDED",
        status = HttpStatus.BAD_REQUEST,
        title = "Refund amount exceeds the refundable amount",
    ),
    REFUND_DECLINED(
        code = "PAYMENT.REFUND_DECLINED",
        status = HttpStatus.PAYMENT_REQUIRED,
        title = "Refund declined",
    ),
    PROVIDER_PAYMENT_NOT_FOUND(
        code = "PAYMENT.PROVIDER_PAYMENT_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "The payment provider does not know this payment",
    ),
    SUBSCRIPTION_NOT_FOUND(
        code = "PAYMENT.SUBSCRIPTION_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        title = "Subscription not found",
    ),
    ALREADY_SUBSCRIBED(
        code = "PAYMENT.ALREADY_SUBSCRIBED",
        status = HttpStatus.CONFLICT,
        title = "Already subscribed",
    ),
    SUBSCRIPTION_NOT_ACTIVATABLE(
        code = "PAYMENT.SUBSCRIPTION_NOT_ACTIVATABLE",
        status = HttpStatus.CONFLICT,
        title = "Subscription cannot be started",
    ),
    SUBSCRIPTION_NOT_CANCELABLE(
        code = "PAYMENT.SUBSCRIPTION_NOT_CANCELABLE",
        status = HttpStatus.CONFLICT,
        title = "Subscription cannot be canceled",
    ),
    PLAN_CHANGE_NOT_ALLOWED(
        code = "PAYMENT.PLAN_CHANGE_NOT_ALLOWED",
        status = HttpStatus.CONFLICT,
        title = "The plan cannot be changed",
    ),
    PLAN_CHANGE_CHARGE_CHANGED(
        code = "PAYMENT.PLAN_CHANGE_CHARGE_CHANGED",
        status = HttpStatus.CONFLICT,
        title = "The charge for the plan change changed",
    ),
    UNAVAILABLE(
        code = "PAYMENT.UNAVAILABLE",
        status = HttpStatus.SERVICE_UNAVAILABLE,
        title = "Payment unavailable",
    ),
}
