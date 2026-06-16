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
}
