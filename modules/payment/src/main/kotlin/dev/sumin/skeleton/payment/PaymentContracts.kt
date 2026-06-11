package dev.sumin.skeleton.payment

data class PaymentAmount(
    val amount: Long,
    val currency: String,
) {
    init {
        require(amount >= 0) { "Payment amount must not be negative" }
        require(currency.isNotBlank()) { "Payment currency must not be blank" }
    }

    val normalizedCurrency: String = currency.trim().uppercase()
}

data class IdempotencyKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Idempotency key must not be blank" }
    }
}

data class ProviderTrace(
    val provider: String,
    val providerRequestId: String? = null,
    val providerOperationId: String? = null,
    val rawCode: String? = null,
    val rawStatus: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class PaymentConfirmRequest(
    val providerPaymentId: String,
    val amount: PaymentAmount,
    val merchantReferenceId: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val providerPayload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }
}

data class PaymentCancelRequest(
    val providerPaymentId: String,
    val amount: PaymentAmount? = null,
    val reason: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val providerPayload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }
}

data class PaymentRefundRequest(
    val providerPaymentId: String,
    val amount: PaymentAmount,
    val reason: String? = null,
    val provider: String? = null,
    val country: String? = null,
    val idempotencyKey: IdempotencyKey? = null,
    val providerPayload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(providerPaymentId.isNotBlank()) { "Provider payment id must not be blank" }
    }
}

enum class PaymentOperationStatus {
    CONFIRMED,
    CANCELED,
    REFUNDED,
    PENDING,
    FAILED,
    UNKNOWN,
}

data class PaymentOperationResult(
    val provider: String,
    val providerPaymentId: String,
    val status: PaymentOperationStatus,
    val amount: PaymentAmount? = null,
    val trace: ProviderTrace,
    val providerOperationId: String? = trace.providerOperationId,
    val rawProviderStatus: String? = trace.rawStatus,
    val attributes: Map<String, String> = emptyMap(),
)

interface PaymentProvider {
    val providerId: String
    val supportedCurrencies: Set<String>
        get() = emptySet()
    val supportedCountries: Set<String>
        get() = emptySet()

    fun confirm(request: PaymentConfirmRequest): PaymentOperationResult

    fun cancel(request: PaymentCancelRequest): PaymentOperationResult

    fun refund(request: PaymentRefundRequest): PaymentOperationResult
}
