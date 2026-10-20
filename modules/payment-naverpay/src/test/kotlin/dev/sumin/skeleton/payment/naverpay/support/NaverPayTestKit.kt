package dev.sumin.skeleton.payment.naverpay.support

import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.payment.CheckoutOrder
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderMismatchAlert
import dev.sumin.skeleton.payment.naverpay.NaverPayProperties
import dev.sumin.skeleton.payment.naverpay.NaverPayProvider
import dev.sumin.skeleton.payment.naverpay.newNaverPayProvider
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

internal fun naverProperties(server: FakeNaverPayServer, returnUrl: String = RETURN_URL, recurrent: Boolean = false): NaverPayProperties =
    NaverPayProperties(
        enabled = true, clientId = server.clientId, clientSecret = server.clientSecret, chainId = server.chainId, baseUrl = server.baseUrl, returnUrl = returnUrl,
        recurrent = if (recurrent) NaverPayProperties.Recurrent(server.recurrentClientId, server.recurrentClientSecret, server.recurrentChainId) else NaverPayProperties.Recurrent(),
    )

/** 정기 인증값이 설정된 모듈 - 구독 능력을 가진 하위 클래스가 선다. */
internal fun naverBillingProvider(server: FakeNaverPayServer, returnUrl: String = RETURN_URL): NaverPayProvider =
    newNaverPayProvider(realHttpClient(), naverProperties(server, returnUrl, recurrent = true), PaymentProviderCredentialAlert.NONE, PaymentProviderMismatchAlert.NONE)

internal fun naverProvider(
    server: FakeNaverPayServer,
    responseTimeout: Duration = GENEROUS,
    returnUrl: String = RETURN_URL,
    mismatchAlert: PaymentProviderMismatchAlert = PaymentProviderMismatchAlert.NONE,
): NaverPayProvider =
    NaverPayProvider(httpClient = realHttpClient(responseTimeout), properties = naverProperties(server, returnUrl), mismatchAlert = mismatchAlert)

/** 서버가 결제창을 예약하고(`checkout`) 구매자가 그 창에서 결제를 마쳐 돌려받은 결제 번호. */
internal fun payAtWindow(server: FakeNaverPayServer, provider: NaverPayProvider, orderId: String = "ord_abcdef123456", amount: Long = 3_000): String {
    val instruction = provider.checkout(checkoutOrder(orderId, amount))
    return server.buyerPays(instruction.attributes.getValue("windowUrl").substringAfterLast('/'))
}

/** 장부가 이미 만든 주문의 결제창(주문을 만들 때 예약됐다)에서 결제를 마친 결제 번호. */
internal fun payOrderWindow(server: FakeNaverPayServer, orderId: String): String =
    server.buyerPays(server.reservations().last { it.merchantPayKey == orderId }.reserveId)

internal fun checkoutOrder(orderId: String = "ord_abcdef123456", amount: Long = 3_000, accountId: String? = "acc_1") =
    CheckoutOrder(orderId = orderId, orderName = "응원하기", amount = PaymentAmount(amount, "KRW"), accountId = accountId)
