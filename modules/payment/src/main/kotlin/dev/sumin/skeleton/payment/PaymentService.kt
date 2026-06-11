package dev.sumin.skeleton.payment

class PaymentService(
    private val router: PaymentProviderRouter,
) {
    fun confirm(request: PaymentConfirmRequest): PaymentOperationResult =
        router.resolve(
            provider = request.provider,
            amount = request.amount,
            country = request.country,
        ).confirm(request)

    fun cancel(request: PaymentCancelRequest): PaymentOperationResult =
        router.resolve(
            provider = request.provider,
            amount = request.amount,
            country = request.country,
        ).cancel(request)

    fun refund(request: PaymentRefundRequest): PaymentOperationResult =
        router.resolve(
            provider = request.provider,
            amount = request.amount,
            country = request.country,
        ).refund(request)
}
