package dev.sumin.skeleton.payment.stripe

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentProvider
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.ProviderTrace
import org.springframework.http.HttpHeaders

class StripePaymentProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: StripePaymentProperties,
) : PaymentProvider {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries

    private val errorMapper = StripePaymentErrorMapper(providerId = providerId)

    init {
        require(providerId.isNotBlank()) { "Stripe payment providerId must not be blank" }
        require(properties.secretKey.isNotBlank()) { "Stripe payment secretKey must not be blank" }
    }

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        val response = requireNotNull(
            httpClient.postForm(
                clientName = properties.clientName,
                path = properties.confirmPath,
                form = request.providerPayload.toForm(),
                responseType = StripePaymentIntentResponse::class.java,
            ) {
                baseUrl(properties.baseUrl)
                uriVariable("paymentIntentId", request.providerPaymentId)
                commonHeaders(request.idempotencyKey)
                loggingTag("payment.stripe.confirm")
                errorMapper(errorMapper)
            }.block(),
        )

        return response.toResult(
            status = response.status.toPaymentIntentStatus(),
            fallbackAmount = request.amount,
        )
    }

    override fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
        val response = requireNotNull(
            httpClient.postForm(
                clientName = properties.clientName,
                path = properties.cancelPath,
                form = cancelForm(request),
                responseType = StripePaymentIntentResponse::class.java,
            ) {
                baseUrl(properties.baseUrl)
                uriVariable("paymentIntentId", request.providerPaymentId)
                commonHeaders(request.idempotencyKey)
                loggingTag("payment.stripe.cancel")
                errorMapper(errorMapper)
            }.block(),
        )

        return response.toResult(
            status = PaymentOperationStatus.CANCELED,
            fallbackAmount = request.amount,
        )
    }

    override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        val response = requireNotNull(
            httpClient.postForm(
                clientName = properties.clientName,
                path = properties.refundPath,
                form = refundForm(request),
                responseType = StripeRefundResponse::class.java,
            ) {
                baseUrl(properties.baseUrl)
                commonHeaders(request.idempotencyKey)
                loggingTag("payment.stripe.refund")
                errorMapper(errorMapper)
            }.block(),
        )

        return response.toResult(fallbackAmount = request.amount)
    }

    private fun cancelForm(request: PaymentCancelRequest): Map<String, String> =
        linkedMapOf<String, String>().apply {
            request.reason?.takeIf { it.isNotBlank() }?.let { put("cancellation_reason", it) }
            putAll(request.providerPayload.toForm())
        }

    private fun refundForm(request: PaymentRefundRequest): Map<String, String> =
        linkedMapOf(
            "payment_intent" to request.providerPaymentId,
            "amount" to request.amount.amount.toString(),
        ).apply {
            request.reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) }
            putAll(request.providerPayload.toForm())
        }

    private fun Map<String, Any?>.toForm(): Map<String, String> =
        entries
            .filter { it.value != null }
            .associate { it.key to it.value.toString() }

    private fun dev.sumin.skeleton.common.http.ExternalHttpRequestSpec.commonHeaders(idempotencyKey: IdempotencyKey?) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.secretKey}")
        idempotencyKey?.let { header("Idempotency-Key", it.value) }
    }

    private fun StripePaymentIntentResponse.toResult(
        status: PaymentOperationStatus,
        fallbackAmount: PaymentAmount?,
    ): PaymentOperationResult =
        PaymentOperationResult(
            provider = providerId,
            providerPaymentId = id,
            status = status,
            amount = amount.toPaymentAmount(currency = currency, fallback = fallbackAmount),
            trace = ProviderTrace(
                provider = providerId,
                providerRequestId = requestId,
                providerOperationId = latestCharge,
                rawStatus = this.status,
            ),
        )

    private fun StripeRefundResponse.toResult(fallbackAmount: PaymentAmount): PaymentOperationResult =
        PaymentOperationResult(
            provider = providerId,
            providerPaymentId = paymentIntent ?: id,
            status = status.toRefundStatus(),
            amount = amount.toPaymentAmount(currency = currency, fallback = fallbackAmount),
            trace = ProviderTrace(
                provider = providerId,
                providerRequestId = requestId,
                providerOperationId = id,
                rawStatus = status,
            ),
        )

    private fun Long?.toPaymentAmount(
        currency: String?,
        fallback: PaymentAmount?,
    ): PaymentAmount? {
        val value = this ?: return fallback
        val responseCurrency = currency ?: fallback?.currency ?: return fallback
        return PaymentAmount(amount = value, currency = responseCurrency)
    }

    private fun String?.toPaymentIntentStatus(): PaymentOperationStatus =
        when (this?.lowercase()) {
            "succeeded" -> PaymentOperationStatus.CONFIRMED
            "canceled" -> PaymentOperationStatus.CANCELED
            "processing", "requires_action", "requires_capture", "requires_confirmation" ->
                PaymentOperationStatus.PENDING
            "requires_payment_method" -> PaymentOperationStatus.FAILED
            else -> PaymentOperationStatus.UNKNOWN
        }

    private fun String?.toRefundStatus(): PaymentOperationStatus =
        when (this?.lowercase()) {
            "succeeded" -> PaymentOperationStatus.REFUNDED
            "pending" -> PaymentOperationStatus.PENDING
            "failed", "canceled" -> PaymentOperationStatus.FAILED
            else -> PaymentOperationStatus.UNKNOWN
        }
}

data class StripePaymentIntentResponse(
    val id: String,
    val status: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
    @JsonProperty("latest_charge")
    val latestCharge: String? = null,
    @JsonProperty("request_id")
    val requestId: String? = null,
)

data class StripeRefundResponse(
    val id: String,
    val status: String? = null,
    val amount: Long? = null,
    val currency: String? = null,
    @JsonProperty("payment_intent")
    val paymentIntent: String? = null,
    @JsonProperty("request_id")
    val requestId: String? = null,
)
