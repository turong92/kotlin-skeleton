package dev.sumin.skeleton.payment

import java.time.Duration

/**
 * 자동결제(빌링) 능력 — 카드를 한 번 등록해 두고 서버가 나중에 승인한다(스펙 `2026-10-09-subscription-design.md` §6).
 * 결제사의 스케줄러는 쓰지 않는다: **언제 얼마를 받을지는 우리 장부가 정하고** 이 능력은 등록 · 승인 · 삭제만 한다.
 * 토큰([BillingIssue.token])은 공통 코드에게 **불투명한 비밀 문자열**이다 — 결제사가 고객 id · 결제수단 id 를 그 안에 담아도 된다(칸을 더 늘리지 않는다).
 */
interface PaymentBillingGateway : PaymentGateway {
    /**
     * 카드 등록 창을 여는 데 프론트가 필요한 값(공개해도 되는 값만). 값을 낼 수 없으면(공개 키가 비었거나 시크릿과 모드가 다름) null.
     * 설정만 보고 답한다 — 외부 호출 없음.
     */
    fun billingSetup(customerKey: String): BillingSetup?

    /**
     * 등록 창 값을 낸다 - 토스는 [billingSetup] 그대로(창 값이 설정뿐이다). **서버가 먼저 결제사를 불러 예약해야 창을 열 수 있는 결제사**(카카오페이 `ready` · 네이버페이 등록 예약)는 겹쳐 쓴다.
     * 결제사 호출이므로 **트랜잭션 밖**에서 불리고, 못 하면 던진다. [PreparedBilling.reference] 는 구독 줄의 첫 거래 번호 칸에 박혀 `activate` 에서 [issue] 로 돌아온다.
     */
    fun prepareBilling(request: BillingPrepareRequest): PreparedBilling? = billingSetup(request.customerKey)?.let { PreparedBilling(it, null) }

    /**
     * [prepareBilling] 이 준 창(예약)이 유효한 시간 - 있으면 같은 요청 키의 재전송이 이 시간 안에서는 결제사를 다시 부르지 않고 **저장된 첫 창을 그대로** 돌려준다
     * (예약을 다시 하면 번호가 바뀌어 앞 창의 승인이 실패한다). null 이면 재전송마다 다시 예약한다(기본 - 토스는 창 값이 설정뿐이고 네이버페이는 지금처럼 다시 예약한다).
     */
    val registrationWindowLifetime: Duration?
        get() = null

    /**
     * **등록이 곧 첫 결제**인 결제사(카카오페이: 1회차를 플랜 금액으로 결제하고 그 승인에서 SID 를 받는다). true 면 `activate` 는 [issue] 가 아니라 [registerWithCharge] 를 쓴다 -
     * 자리 · 첫 주문을 먼저 커밋하고(락 밖에서) 한 번 부르며, 첫 주문 번호는 [BillingPrepareRequest.firstOrderId] 와 같다.
     */
    val registrationCharges: Boolean
        get() = false

    /** [registrationCharges] 인 결제사만. 돈이 움직이는 호출이라 [charge] 와 같은 실패 분류다(확정 거절 / 모름). 결과의 토큰이 없으면(SID 를 못 읽었다) 구독은 켜지되 토큰이 비어 있다. */
    fun registerWithCharge(request: RegisterWithChargeRequest): RegisteredCharge =
        throw UnsupportedOperationException("$providerId registers with a separate step")

    /**
     * 등록 창이 돌려준 값([providerPayload], 토스는 `authKey`)을 토큰으로 바꾼다. 돈이 움직이지 않는다.
     * 결제사가 **확정 거절**하면 [PaymentProviderException]([PaymentProviderError.definitive]), 모르면 그 밖의 예외다.
     */
    fun issue(customerKey: String, providerPayload: Map<String, Any?>): BillingIssue

    /**
     * 돌아온 창이 준 값([providerPayload])과 예약 번호가 결제사가 받을 **모양**인가 - `activate` 가 자리를 선점하기 **전에** 묻는다(문자열 검사만, 외부 호출 없음). false 면 선점 없이 400 이다 -
     * 선점 뒤에 모양이 틀려 거절하면 「모름」으로 읽혀 구독이 자리를 쥔 채 굳는다. 기본은 늘 true(모양을 따로 아는 결제사만 겹쳐 쓴다).
     */
    fun acceptsRegistration(providerPayload: Map<String, Any?>, reference: String?): Boolean = true

    /** [prepareBilling] 이 준 [PreparedBilling.reference] 를 함께 받는 발급 - 돌아온 값이 그 예약의 것인지 결제사가 확인한다(네이버: 다르면 결제사를 부르지 않고 던진다). 토스는 무시한다. */
    fun issue(customerKey: String, providerPayload: Map<String, Any?>, reference: String?): BillingIssue = issue(customerKey, providerPayload)

    /** 토큰으로 승인한다. 결과는 승인([PaymentConfirmGateway.confirm])과 같은 모양이고 실패 분류도 같다(확정 거절 / 모름). */
    fun charge(request: BillingChargeRequest): PaymentOperationResult

    /**
     * 확정 거절 코드를 `DECLINED` 응답의 `data.category` 로 옮긴다([PaymentConfirmGateway.confirmDeclineCategory] 와 같은 뜻 — 결제사 코드 어휘는 결제사 모듈만 안다). 기본은 [ConfirmDeclineCategory.OTHER].
     */
    fun billingDeclineCategory(providerCode: String?): ConfirmDeclineCategory = ConfirmDeclineCategory.OTHER

    /** 토큰을 지운다. **이미 없으면 정상 반환한다**(멱등). 못 지우면 던진다. */
    fun revoke(customerKey: String, token: String)
}

/** `POST /subscriptions` 응답의 결제사 몫. 프론트가 [kind] 로 갈라 카드 등록 창을 연다. 공개해도 되는 값만 담는다. */
data class BillingSetup(
    val provider: String,
    val kind: String,
    val testMode: Boolean,
    /** 공개 키 - 리다이렉트 결제사(카카오페이 · 네이버페이)는 공개 키가 없어 null. */
    val clientKey: String?,
    val customerKey: String,
    val attributes: Map<String, String> = emptyMap(),
)

/** [prepareBilling] 의 입력 - 구독 줄 · 플랜의 값. [firstOrderId] 는 첫 주문의 번호(`ready` 의 `partner_order_id` 처럼 미리 정해야 하는 결제사용), [returnOrigin] 은 화면이 보낸 origin(허용 목록에 있을 때만 쓰인다). */
data class BillingPrepareRequest(
    val subscriptionId: String,
    val customerKey: String,
    val planName: String,
    val amount: PaymentAmount,
    val firstOrderId: String,
    val returnOrigin: String? = null,
    val accountId: String? = null,
) {
    override fun toString(): String = "BillingPrepareRequest(subscriptionId=$subscriptionId, amount=${amount.amount} ${amount.normalizedCurrency}, customerKey=<redacted>)"
}

/** [reference] 는 결제사가 먼저 정한 번호(카카오 `tid` · 네이버 `reserveId`) - 구독 줄의 첫 거래 번호 칸에 박힌다. 없는 결제사(토스)는 null. */
data class PreparedBilling(val setup: BillingSetup, val reference: String?)

/** [registerWithCharge] 의 입력 - 승인 토큰은 돌아온 창이 준 값([providerPayload])이고 [reference] 는 예약 때의 번호다. */
data class RegisterWithChargeRequest(
    val customerKey: String,
    val providerPayload: Map<String, Any?>,
    val reference: String?,
    val orderId: String,
    val orderName: String,
    val amount: PaymentAmount,
    val idempotencyKey: IdempotencyKey,
    val accountId: String? = null,
) {
    override fun toString(): String = "RegisterWithChargeRequest(orderId=$orderId, amount=${amount.amount} ${amount.normalizedCurrency}, customerKey=<redacted>)"
}

/** [issue] 는 SID 를 읽었으면 값이 있고 못 읽었으면 null(돈은 움직였다 - 결제 결과는 [result]). */
data class RegisteredCharge(val issue: BillingIssue?, val result: PaymentOperationResult)

/** [token] 은 비밀이라 [toString] 이 찍지 않는다. [card] 는 끝 네 글자만 가진 표시용. */
data class BillingIssue(
    val token: String,
    val card: PaymentCard?,
) {
    override fun toString(): String = "BillingIssue(token=<redacted>, card=$card)"
}

/** [token] · [customerKey] 는 비밀이라 [toString] 이 찍지 않는다. [idempotencyKey] 는 결제사가 같은 승인을 한 번으로 보게 한다(주문 id). */
data class BillingChargeRequest(
    val token: String,
    val customerKey: String,
    val orderId: String,
    val orderName: String,
    val amount: PaymentAmount,
    val idempotencyKey: IdempotencyKey,
) {
    override fun toString(): String =
        "BillingChargeRequest(orderId=$orderId, amount=${amount.amount} ${amount.normalizedCurrency}, token=<redacted>, customerKey=<redacted>)"
}
