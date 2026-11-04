package dev.sumin.skeleton.payment.appstore

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import tools.jackson.databind.json.JsonMapper

/**
 * App Store Server API 의 오류(`{"errorCode":4040010,"errorMessage":"…"}`)를 [PaymentProviderException] 으로 옮긴다. **본문은 예외에 싣지 않는다** - 숫자 `errorCode` 만 꺼낸다.
 * 조회뿐이라 확정 거절(`definitive`)은 없다. 404 는 「애플이 그 거래를 모른다」이고 부르는 쪽이 읽는다.
 */
internal class AppStoreErrorMapper(private val providerId: String, private val mapper: JsonMapper = JsonMapper.builder().build()) : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): PaymentProviderException {
        val code = codeOf(context.upstreamBody)
        return PaymentProviderException(
            providerError = PaymentProviderError(
                provider = providerId,
                code = code,
                message = null,
                trace = ProviderTrace(provider = providerId, rawCode = code, rawStatus = context.upstreamStatus.toString()),
                upstreamStatus = context.upstreamStatus,
                retryable = context.upstreamStatus == 429 || context.upstreamStatus >= 500,
                rawBody = null,
            ),
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
        )
    }

    private fun codeOf(body: String): String? =
        try {
            mapper.readTree(body)?.get("errorCode")?.takeIf { it.isNumber }?.asLong()?.toString()
        } catch (_: Exception) {
            null
        }
}
