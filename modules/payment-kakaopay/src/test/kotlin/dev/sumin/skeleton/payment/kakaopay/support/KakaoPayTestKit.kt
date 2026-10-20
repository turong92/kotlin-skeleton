package dev.sumin.skeleton.payment.kakaopay.support

import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.kakaopay.KakaoPayProperties
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.kakaopay.KakaoPayProvider
import dev.sumin.skeleton.payment.kakaopay.newKakaoPayProvider
import java.time.Duration
import org.springframework.web.reactive.function.client.WebClient

/** 부하가 큰 호스트에서도 같은 결과여야 한다 - 정상 경로의 상한은 넉넉하게, 시간 초과 시험은 서버가 응답을 붙잡는 방식으로. */
internal val GENEROUS: Duration = Duration.ofSeconds(30)

internal const val RETURN_URL = "https://app.example.test/payments/return"

/** 진짜 [DefaultExternalHttpClient] - 가짜 서버와 실제 HTTP 로 말한다. */
internal fun realHttpClient(responseTimeout: Duration = GENEROUS): ExternalHttpClient =
    DefaultExternalHttpClient(
        webClientBuilder = WebClient.builder(),
        properties = OutboundHttpProperties(defaultConnectTimeout = GENEROUS, defaultResponseTimeout = responseTimeout),
        defaultErrorMapper = DefaultExternalHttpErrorMapper(),
        customizers = emptyList(),
    )

internal fun kakaoProperties(server: FakeKakaoPayServer, returnUrl: String = RETURN_URL, subscription: Boolean = false): KakaoPayProperties =
    KakaoPayProperties(enabled = true, secretKey = server.secretKey, cid = server.cid, subscriptionCid = if (subscription) server.subscriptionCid else "", baseUrl = server.baseUrl, returnUrl = returnUrl)

internal fun kakaoProvider(server: FakeKakaoPayServer, responseTimeout: Duration = GENEROUS, returnUrl: String = RETURN_URL): KakaoPayProvider =
    KakaoPayProvider(httpClient = realHttpClient(responseTimeout), properties = kakaoProperties(server, returnUrl))

/** 정기 가맹점 코드가 설정된 모듈 - 구독 능력을 가진 하위 클래스가 선다. */
internal fun kakaoBillingProvider(server: FakeKakaoPayServer, returnUrl: String = RETURN_URL): KakaoPayProvider =
    newKakaoPayProvider(realHttpClient(), kakaoProperties(server, returnUrl, subscription = true), PaymentProviderCredentialAlert.NONE)

internal fun checkoutOrder(orderId: String = "ord_abcdef123456", amount: Long = 3_000, accountId: String? = "acc_1") =
    CheckoutOrder(orderId = orderId, orderName = "응원하기", amount = PaymentAmount(amount, "KRW"), accountId = accountId)
