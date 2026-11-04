package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.payment.PaymentProviderError
import dev.sumin.skeleton.payment.PaymentProviderException
import dev.sumin.skeleton.payment.ProviderTrace
import java.net.URI

/** 구글이 200 으로 답했는데 쓸 수 없는 답 - 호출한 쪽은 일시 실패처럼 다시 한다(구매 확인은 앱이 다시 보내고, 알림 해석은 수신함 줄이 재시도된다). */
internal object GooglePlayFailures {
    const val UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE"
    const val KEYS_UNAVAILABLE = "KEYS_UNAVAILABLE"

    fun unreadable(properties: GooglePlayProperties): PaymentProviderException = failure(properties, UNREADABLE_RESPONSE)

    fun failure(properties: GooglePlayProperties, code: String): PaymentProviderException =
        PaymentProviderException(
            providerError = PaymentProviderError(
                provider = properties.providerId, code = code, message = null, trace = ProviderTrace(provider = properties.providerId, rawCode = code), upstreamStatus = 200, retryable = true,
            ),
            clientName = properties.clientName,
            method = "GET",
            uri = URI.create(properties.resolvedBaseUrl),
        )
}
