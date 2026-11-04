package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpErrorMapper
import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import java.net.URI
import tools.jackson.databind.json.JsonMapper

/**
 * 구글 API 의 오류(`{"error":{"code":404,"message":"…","status":"NOT_FOUND"}}`)를 [PaymentProviderException] 으로 옮긴다. **본문은 예외에 싣지 않는다** - `error.status` 코드만 꺼낸다
 * (메시지에 구매 토큰이 들어 있을 수 있다). 404 는 「구글이 그걸 모른다」이고 부르는 쪽이 읽는다.
 */
internal class GooglePlayErrorMapper(private val providerId: String, private val mapper: JsonMapper = JsonMapper.builder().build()) : ExternalHttpErrorMapper {
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
            uri = withoutToken(context.uri),
        )
    }

    /** 구매 토큰이 주소 경로에 들어 있다 - 예외 · 로그에는 가린 주소를 싣는다. */
    private fun withoutToken(uri: URI): URI = URI.create(uri.toString().substringBefore("?").replace(Regex("(/tokens/)[^/:]+"), "$1redacted"))

    private fun codeOf(body: String): String? =
        try {
            mapper.readTree(body)?.get("error")?.get("status")?.takeIf { it.isString }?.asString()?.takeIf { CODE.matches(it) }
        } catch (_: Exception) {
            null
        }

    private companion object {
        val CODE = Regex("[A-Z_]{1,40}")
    }
}
