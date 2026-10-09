package dev.sumin.skeleton.payment

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
     * 등록 창이 돌려준 값([providerPayload], 토스는 `authKey`)을 토큰으로 바꾼다. 돈이 움직이지 않는다.
     * 결제사가 **확정 거절**하면 [PaymentProviderException]([PaymentProviderError.definitive]), 모르면 그 밖의 예외다.
     */
    fun issue(customerKey: String, providerPayload: Map<String, Any?>): BillingIssue

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
    val clientKey: String,
    val customerKey: String,
    val attributes: Map<String, String> = emptyMap(),
)

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
