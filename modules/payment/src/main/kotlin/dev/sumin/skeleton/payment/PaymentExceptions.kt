package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.http.ExternalHttpException
import java.net.URI
import org.springframework.http.HttpStatus

class PaymentProviderNotFoundException(
    providerId: String,
) : ApplicationException(
    message = "Payment provider not found or disabled: $providerId",
    status = HttpStatus.BAD_REQUEST,
    title = "Payment provider not found",
)

class PaymentRoutingException(
    message: String,
) : ApplicationException(
    message = message,
    status = HttpStatus.BAD_REQUEST,
    title = "Payment provider routing failed",
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
    status = HttpStatus.BAD_GATEWAY,
    title = "Payment provider error",
    cause = cause,
)
