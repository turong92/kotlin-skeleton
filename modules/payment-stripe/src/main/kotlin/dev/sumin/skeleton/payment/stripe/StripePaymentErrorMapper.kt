package dev.sumin.skeleton.payment.stripe

import com.fasterxml.jackson.annotation.JsonProperty
import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class StripePaymentErrorMapper(
    private val providerId: String,
    private val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build(),
) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException {
        val stripeError = parse(context.upstreamBody)?.error
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = stripeError?.code ?: stripeError?.declineCode,
                message = stripeError?.message,
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = context.trace.traceId ?: context.upstreamHeaders.stripeRequestId(),
                    rawCode = stripeError?.type,
                    rawStatus = context.upstreamStatus.toString(),
                    metadata = stripeError?.requestLogUrl
                        ?.let { mapOf("requestLogUrl" to it) }
                        ?: emptyMap(),
                ),
                upstreamStatus = context.upstreamStatus,
                retryable = context.upstreamStatus == 429 || context.upstreamStatus >= 500,
                rawBody = context.upstreamBody.take(MAX_BODY_LENGTH),
            ),
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
        )
    }

    private fun parse(body: String): StripeErrorEnvelope? =
        runCatching {
            mapper.readValue(body, StripeErrorEnvelope::class.java)
        }.getOrNull()

    private data class StripeErrorEnvelope(
        val error: StripeError? = null,
    )

    private data class StripeError(
        val type: String? = null,
        val code: String? = null,
        val message: String? = null,
        @JsonProperty("decline_code")
        val declineCode: String? = null,
        @JsonProperty("request_log_url")
        val requestLogUrl: String? = null,
    )

    private fun org.springframework.http.HttpHeaders.stripeRequestId(): String? =
        STRIPE_REQUEST_ID_HEADERS.firstNotNullOfOrNull { name ->
            getFirst(name)?.takeIf { it.isNotBlank() }
        }

    private companion object {
        const val MAX_BODY_LENGTH = 2_048
        val STRIPE_REQUEST_ID_HEADERS = listOf(
            "Request-Id",
            "Stripe-Request-Id",
            "X-Request-Id",
        )
    }
}
