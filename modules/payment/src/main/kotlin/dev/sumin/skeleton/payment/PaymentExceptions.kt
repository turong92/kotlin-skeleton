package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.http.ExternalHttpException
import java.net.URI

class PaymentProviderNotFoundException(
    providerId: String,
) : ApplicationException(
    message = "Payment provider not found or disabled: $providerId",
    errorCode = PaymentErrorCode.PROVIDER_NOT_FOUND,
)

class PaymentRoutingException(
    message: String,
) : ApplicationException(
    message = message,
    errorCode = PaymentErrorCode.ROUTING_FAILED,
)

data class PaymentProviderError(
    val provider: String,
    val code: String? = null,
    val message: String? = null,
    val trace: ProviderTrace,
    val upstreamStatus: Int,
    val retryable: Boolean,
    val rawBody: String? = null,
)

class PaymentProviderException(
    val providerError: PaymentProviderError,
    clientName: String,
    method: String,
    uri: URI,
    cause: Throwable? = null,
) : ExternalHttpException(
    message = providerError.message
        ?: providerError.code
        ?: "Payment provider '${providerError.provider}' returned HTTP ${providerError.upstreamStatus}",
    clientName = clientName,
    method = method,
    uri = uri,
    upstreamStatus = providerError.upstreamStatus,
    retryable = providerError.retryable,
    errorCode = PaymentErrorCode.PROVIDER_ERROR,
    data = providerError.toSafeData(),
    cause = cause,
)

private fun PaymentProviderError.toSafeData(): Map<String, Any?> =
    mapOf(
        "provider" to provider,
        "providerCode" to code,
        "providerMessage" to message,
        "providerTrace" to trace,
        "upstreamStatus" to upstreamStatus,
        "retryable" to retryable,
    )
