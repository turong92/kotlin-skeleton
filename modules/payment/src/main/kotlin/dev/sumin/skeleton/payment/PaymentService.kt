package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

class PaymentService(
    private val router: PaymentProviderRouter,
    private val failureHandlers: List<PaymentFailureHandler> = emptyList(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        val provider = router.resolve(
            provider = request.provider,
            amount = request.amount,
            country = request.country,
        )
        return execute(
            eventFactory = { error ->
                PaymentFailureEvent(
                    operation = PaymentOperation.CONFIRM,
                    provider = provider.providerId,
                    providerPaymentId = request.providerPaymentId,
                    amount = request.amount,
                    country = request.country,
                    merchantReferenceId = request.merchantReferenceId,
                    idempotencyKey = request.idempotencyKey,
                    exception = error,
                )
            },
        ) {
            provider.confirm(request)
        }
    }

    fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
        val provider = router.resolve(
            provider = request.provider,
            amount = request.amount,
            country = request.country,
        )
        return execute(
            eventFactory = { error ->
                PaymentFailureEvent(
                    operation = PaymentOperation.CANCEL,
                    provider = provider.providerId,
                    providerPaymentId = request.providerPaymentId,
                    amount = request.amount,
                    country = request.country,
                    reason = request.reason,
                    idempotencyKey = request.idempotencyKey,
                    exception = error,
                )
            },
        ) {
            provider.cancel(request)
        }
    }

    fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        val provider = router.resolve(
            provider = request.provider,
            amount = request.amount,
            country = request.country,
        )
        return execute(
            eventFactory = { error ->
                PaymentFailureEvent(
                    operation = PaymentOperation.REFUND,
                    provider = provider.providerId,
                    providerPaymentId = request.providerPaymentId,
                    amount = request.amount,
                    country = request.country,
                    reason = request.reason,
                    idempotencyKey = request.idempotencyKey,
                    exception = error,
                )
            },
        ) {
            provider.refund(request)
        }
    }

    private fun execute(
        eventFactory: (Exception) -> PaymentFailureEvent,
        action: () -> PaymentOperationResult,
    ): PaymentOperationResult =
        try {
            action()
        } catch (error: Exception) {
            publishFailure(eventFactory(error))
            throw error
        }

    private fun publishFailure(event: PaymentFailureEvent) {
        failureHandlers.forEach { handler ->
            runCatching { handler.handle(event) }
                .onFailure { handlerError ->
                    log.warn(
                        "Payment failure handler failed. operation={}, provider={}, providerPaymentId={}",
                        event.operation,
                        event.provider,
                        event.providerPaymentId,
                        handlerError,
                    )
                }
        }
    }

}
