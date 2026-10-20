package dev.sumin.skeleton.payment.kakaopay

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingPrepareRequest
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PreparedBilling
import dev.sumin.skeleton.payment.RegisterWithChargeRequest
import dev.sumin.skeleton.payment.RegisteredCharge

/**
 * 카카오페이 정기 결제 능력을 더한 [KakaoPayProvider]. **정기 가맹점 코드(`subscription-cid`)가 있을 때만** 이 클래스로 만든다 - 능력은 인터페이스로 선언되고 라우터는 `is` 로 보므로,
 * 설정이 비면 빈 자체가 단건 클래스여야 구독 능력이 보이지 않는다. 카드 등록 단계가 따로 없다: **1회차를 플랜 금액으로 결제하고 그 승인(`approve`)에서 빌링키(SID)를 받는다**([registrationCharges]).
 * 결제사가 아는 일을 우리 장부가 정한다 - 스케줄러는 쓰지 않는다(갱신 때마다 [charge] 가 `subscription` 을 부른다).
 */
class KakaoPayBillingProvider(
    httpClient: ExternalHttpClient,
    private val kakaoProperties: KakaoPayProperties,
    credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
) : KakaoPayProvider(httpClient, kakaoProperties, credentialAlert), PaymentBillingGateway {
    init {
        require(kakaoProperties.subscriptionCid.isNotBlank()) { "KakaoPayBillingProvider needs skeleton.payment-kakaopay.subscription-cid" }
    }

    override val registrationCharges: Boolean = true

    /** 창 값 - 서버가 `ready` 를 부른 뒤에야 낸다([prepareBilling]). 설정만으로는 낼 수 없다. */
    override fun billingSetup(customerKey: String): BillingSetup? = null

    override fun prepareBilling(request: BillingPrepareRequest): PreparedBilling? = prepareSubscription(request)

    override fun acceptsRegistration(providerPayload: Map<String, Any?>, reference: String?): Boolean = acceptsSubscriptionRegistration(providerPayload, reference)

    /** 카드 등록이 따로 없다 - 등록은 첫 결제다([registerWithCharge]). */
    override fun issue(customerKey: String, providerPayload: Map<String, Any?>) = throw UnsupportedOperationException("KakaoPay registers with its first payment")

    override fun registerWithCharge(request: RegisterWithChargeRequest): RegisteredCharge = registerSubscription(request)

    override fun charge(request: BillingChargeRequest): PaymentOperationResult = chargeSubscription(request)

    override fun revoke(customerKey: String, token: String) = revokeSubscription(token)

    override fun billingDeclineCategory(providerCode: String?): ConfirmDeclineCategory = ConfirmDeclineCategory.OTHER
}

/** 정기 가맹점 코드가 설정돼 있으면 정기 능력을 가진 빈, 아니면 단건만. */
internal fun newKakaoPayProvider(httpClient: ExternalHttpClient, properties: KakaoPayProperties, credentialAlert: PaymentProviderCredentialAlert): KakaoPayProvider =
    if (properties.subscriptionCid.isBlank()) KakaoPayProvider(httpClient, properties, credentialAlert) else KakaoPayBillingProvider(httpClient, properties, credentialAlert)
