package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpResponse
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCancelRequest
import dev.sumin.skeleton.payment.PaymentConfirmRequest
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentOperationStatus
import dev.sumin.skeleton.payment.PaymentProvider
import dev.sumin.skeleton.payment.PaymentRefundRequest
import dev.sumin.skeleton.payment.ProviderTrace
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.springframework.http.HttpHeaders

class TossPaymentProvider(
    private val httpClient: ExternalHttpClient,
    private val properties: TossPaymentProperties,
) : PaymentProvider {
    override val providerId: String = properties.providerId
    override val supportedCurrencies: Set<String> = properties.supportedCurrencies
    override val supportedCountries: Set<String> = properties.supportedCountries

    private val errorMapper = TossPaymentErrorMapper(providerId = providerId)

    init {
        require(providerId.isNotBlank()) { "Toss payment providerId must not be blank" }
        require(properties.secretKey.isNotBlank()) { "Toss payment secretKey must not be blank" }
    }

    override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult {
        val response = requireNotNull(
            httpClient.postResponse(
                clientName = properties.clientName,
                path = properties.confirmPath,
                body = confirmBody(request),
                responseType = TossPaymentResponse::class.java,
            ) {
                baseUrl(properties.baseUrl)
                commonHeaders(request.idempotencyKey)
                loggingTag("payment.toss.confirm")
                errorMapper(errorMapper)
            }.block(),
        )

        return response.body.toResult(
            fallbackPaymentId = request.providerPaymentId,
            fallbackAmount = request.amount,
            status = response.body.status.toConfirmStatus(),
            providerRequestId = response.trace.traceId ?: response.headers.tossTraceId(),
        )
    }

    override fun cancel(request: PaymentCancelRequest): PaymentOperationResult {
        val response = postCancel(
            providerPaymentId = request.providerPaymentId,
            amount = request.amount,
            reason = request.reason,
            idempotencyKey = request.idempotencyKey,
            providerPayload = request.providerPayload,
            loggingTag = "payment.toss.cancel",
        )

        return response.body.toResult(
            fallbackPaymentId = request.providerPaymentId,
            fallbackAmount = request.amount,
            status = response.body.status.toCancelStatus(),
            providerRequestId = response.trace.traceId ?: response.headers.tossTraceId(),
        )
    }

    override fun refund(request: PaymentRefundRequest): PaymentOperationResult {
        val response = postCancel(
            providerPaymentId = request.providerPaymentId,
            amount = request.amount,
            reason = request.reason,
            idempotencyKey = request.idempotencyKey,
            providerPayload = request.providerPayload,
            loggingTag = "payment.toss.refund",
        )

        return response.body.toResult(
            fallbackPaymentId = request.providerPaymentId,
            fallbackAmount = request.amount,
            status = PaymentOperationStatus.REFUNDED,
            providerRequestId = response.trace.traceId ?: response.headers.tossTraceId(),
        )
    }

    private fun postCancel(
        providerPaymentId: String,
        amount: PaymentAmount?,
        reason: String?,
        idempotencyKey: IdempotencyKey?,
        providerPayload: Map<String, Any?>,
        loggingTag: String,
    ): ExternalHttpResponse<TossPaymentResponse> =
        requireNotNull(
            httpClient.postResponse(
                clientName = properties.clientName,
                path = properties.cancelPath,
                body = cancelBody(amount, reason, providerPayload),
                responseType = TossPaymentResponse::class.java,
            ) {
                baseUrl(properties.baseUrl)
                uriVariable("paymentKey", providerPaymentId)
                commonHeaders(idempotencyKey)
                loggingTag(loggingTag)
                errorMapper(errorMapper)
            }.block(),
        )

    private fun confirmBody(request: PaymentConfirmRequest): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "paymentKey" to request.providerPaymentId,
            "amount" to request.amount.amount,
        ).apply {
            request.merchantReferenceId?.takeIf { it.isNotBlank() }?.let { put("orderId", it) }
            putAll(request.providerPayload)
        }

    private fun cancelBody(
        amount: PaymentAmount?,
        reason: String?,
        providerPayload: Map<String, Any?>,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            reason?.takeIf { it.isNotBlank() }?.let { put("cancelReason", it) }
            amount?.let {
                put("cancelAmount", it.amount)
                put("currency", it.normalizedCurrency)
            }
            putAll(providerPayload)
        }

    private fun dev.sumin.skeleton.common.http.ExternalHttpRequestSpec.commonHeaders(idempotencyKey: IdempotencyKey?) {
        header(HttpHeaders.AUTHORIZATION, basicAuthorization())
        vendorTraceHeaders(*TOSS_TRACE_HEADERS.toTypedArray())
        idempotencyKey?.let { header("Idempotency-Key", it.value) }
    }

    private fun basicAuthorization(): String {
        val token = Base64.getEncoder()
            .encodeToString("${properties.secretKey}:".toByteArray(StandardCharsets.UTF_8))
        return "Basic $token"
    }

    private fun TossPaymentResponse.toResult(
        fallbackPaymentId: String,
        fallbackAmount: PaymentAmount?,
        status: PaymentOperationStatus,
        providerRequestId: String? = null,
    ): PaymentOperationResult =
        PaymentOperationResult(
            provider = providerId,
            providerPaymentId = paymentKey ?: fallbackPaymentId,
            status = status,
            amount = amount(fallbackAmount),
            trace = ProviderTrace(
                provider = providerId,
                providerRequestId = traceId ?: providerRequestId,
                providerOperationId = transactionKey,
                rawStatus = this.status,
                metadata = listOfNotNull(orderId?.let { "orderId" to it }).toMap(),
            ),
            attributes = listOfNotNull(method?.let { "method" to it }).toMap(),
        )

    private fun TossPaymentResponse.amount(fallback: PaymentAmount?): PaymentAmount? {
        val responseAmount = cancelAmount ?: totalAmount ?: return fallback
        val responseCurrency = currency ?: fallback?.currency ?: return fallback
        return PaymentAmount(amount = responseAmount, currency = responseCurrency)
    }

    private fun String?.toConfirmStatus(): PaymentOperationStatus =
        when (this?.uppercase()) {
            "DONE", "CONFIRMED" -> PaymentOperationStatus.CONFIRMED
            "READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> PaymentOperationStatus.PENDING
            "CANCELED", "PARTIAL_CANCELED" -> PaymentOperationStatus.CANCELED
            "ABORTED", "EXPIRED" -> PaymentOperationStatus.FAILED
            else -> PaymentOperationStatus.UNKNOWN
        }

    private fun String?.toCancelStatus(): PaymentOperationStatus =
        when (this?.uppercase()) {
            "CANCELED", "PARTIAL_CANCELED", "DONE" -> PaymentOperationStatus.CANCELED
            "READY", "IN_PROGRESS" -> PaymentOperationStatus.PENDING
            "ABORTED", "EXPIRED" -> PaymentOperationStatus.FAILED
            else -> PaymentOperationStatus.UNKNOWN
        }

    private fun HttpHeaders.tossTraceId(): String? =
        firstPresentHeader(TOSS_TRACE_HEADERS)

    private fun HttpHeaders.firstPresentHeader(names: List<String>): String? =
        names.firstNotNullOfOrNull { name ->
            getFirst(name)?.takeIf { it.isNotBlank() }
        }

    private companion object {
        val TOSS_TRACE_HEADERS = listOf(
            "X-Toss-Trace-Id",
            "Toss-Trace-Id",
            "TossPayments-Trace-Id",
            "X-Request-Id",
        )
    }
}

data class TossPaymentResponse(
    val paymentKey: String? = null,
    val orderId: String? = null,
    val status: String? = null,
    val totalAmount: Long? = null,
    val cancelAmount: Long? = null,
    val currency: String? = null,
    val method: String? = null,
    val traceId: String? = null,
    val transactionKey: String? = null,
)
