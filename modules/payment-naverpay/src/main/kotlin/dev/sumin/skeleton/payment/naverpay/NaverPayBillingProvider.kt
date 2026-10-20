package dev.sumin.skeleton.payment.naverpay

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.payment.BillingChargeRequest
import dev.sumin.skeleton.payment.BillingIssue
import dev.sumin.skeleton.payment.BillingPrepareRequest
import dev.sumin.skeleton.payment.BillingSetup
import dev.sumin.skeleton.payment.ConfirmDeclineCategory
import dev.sumin.skeleton.payment.PaymentBillingGateway
import dev.sumin.skeleton.payment.PaymentOperationResult
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentProviderMismatchAlert
import dev.sumin.skeleton.payment.PreparedBilling

/**
 * 네이버페이 정기 결제(자동결제) 능력을 더한 [NaverPayProvider]. **정기 인증값(`recurrent.client-id` · `client-secret` · `chain-id`)이 모두 있을 때만** 이 클래스로 만든다 -
 * 능력은 인터페이스로 선언되고 라우터는 `is` 로 보므로, 설정이 비면 빈 자체가 단건 클래스여야 구독 능력이 보이지 않는다.
 * 등록은 돈이 움직이지 않는다: 서버가 등록을 예약하고([prepareBilling]) 구매자가 등록창에서 마치면 [issue] 가 `recurrentId` 를 받는다. 과금은 예약 → 승인 두 걸음이다([charge]).
 */
class NaverPayBillingProvider(
    httpClient: ExternalHttpClient,
    naverProperties: NaverPayProperties,
    credentialAlert: PaymentProviderCredentialAlert = PaymentProviderCredentialAlert.NONE,
    mismatchAlert: PaymentProviderMismatchAlert = PaymentProviderMismatchAlert.NONE,
) : NaverPayProvider(httpClient, naverProperties, credentialAlert, mismatchAlert), PaymentBillingGateway {
    init {
        require(naverProperties.recurrent.configured) { "NaverPayBillingProvider needs skeleton.payment-naverpay.recurrent.client-id, client-secret and chain-id" }
    }

    /** 창 값은 서버가 등록을 예약한 뒤에야 낸다([prepareBilling]). 설정만으로는 낼 수 없다. */
    override fun billingSetup(customerKey: String): BillingSetup? = null

    override fun prepareBilling(request: BillingPrepareRequest): PreparedBilling? = prepareRecurrent(request)

    override fun acceptsRegistration(providerPayload: Map<String, Any?>, reference: String?): Boolean = acceptsRecurrentRegistration(providerPayload, reference)

    override fun issue(customerKey: String, providerPayload: Map<String, Any?>): BillingIssue = throw UnsupportedOperationException("NaverPay needs the reserved registration number")

    override fun issue(customerKey: String, providerPayload: Map<String, Any?>, reference: String?): BillingIssue = issueRecurrent(providerPayload, reference)

    override fun charge(request: BillingChargeRequest): PaymentOperationResult = chargeRecurrent(request)

    override fun revoke(customerKey: String, token: String) = revokeRecurrent(token)

    override fun billingDeclineCategory(providerCode: String?): ConfirmDeclineCategory = NaverPayErrorMapper.declineCategory(providerCode)
}

/** 정기 인증값이 모두 설정돼 있으면 정기 능력을 가진 빈, 아니면 단건만. */
internal fun newNaverPayProvider(
    httpClient: ExternalHttpClient,
    properties: NaverPayProperties,
    credentialAlert: PaymentProviderCredentialAlert,
    mismatchAlert: PaymentProviderMismatchAlert,
): NaverPayProvider =
    if (properties.recurrent.configured) NaverPayBillingProvider(httpClient, properties, credentialAlert, mismatchAlert) else NaverPayProvider(httpClient, properties, credentialAlert, mismatchAlert)
