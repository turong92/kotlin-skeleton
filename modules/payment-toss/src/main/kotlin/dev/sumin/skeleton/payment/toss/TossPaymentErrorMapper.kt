package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class TossPaymentErrorMapper(
    private val providerId: String,
    private val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build(),
) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException {
        val response = parse(context.upstreamBody)
        val code = response?.code ?: response?.error
        val message = response?.message
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = code,
                message = message,
                trace = ProviderTrace(
                    provider = providerId,
                    providerRequestId = response?.traceId,
                    rawCode = code,
                    rawStatus = context.upstreamStatus.toString(),
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

    private fun parse(body: String): TossErrorResponse? =
        runCatching {
            mapper.readValue(body, TossErrorResponse::class.java)
        }.getOrNull()

    private data class TossErrorResponse(
        val code: String? = null,
        val message: String? = null,
        val traceId: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val MAX_BODY_LENGTH = 2_048
    }
}
