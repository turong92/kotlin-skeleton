package dev.sumin.skeleton.payment

enum class PaymentOperation {
    CONFIRM,
    CANCEL,
    REFUND,
}

data class PaymentFailureEvent(
    val operation: PaymentOperation,
    val provider: String,
    val providerPaymentId: String,
    val amount: PaymentAmount?,
    val country: String? = null,
    val merchantReferenceId: String? = null,
    val reason: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val exception: Exception,
)

fun interface PaymentFailureHandler {
    fun handle(event: PaymentFailureEvent)
}
