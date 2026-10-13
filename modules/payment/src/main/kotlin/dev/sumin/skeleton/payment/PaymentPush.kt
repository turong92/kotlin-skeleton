package dev.sumin.skeleton.payment

import java.time.Instant

/**
 * 결제사가 **서명한 웹훅 본문이 곧 사실**인 결제사(Paddle 같은 판매 대행 결제사)의 계약. 토스처럼 웹훅은 신호일 뿐 사실은 조회로 얻는 결제사는 [PaymentWebhookGateway] 만 구현한다.
 * 이 결제사는 `parse` 가 [PushedEvents.RESOURCE_TYPE] 으로 봉투를 내고 `storedPayload` 에 서명을 통과한 원문을 담는다 — 처리 작업이 [interpret] 로 읽어 [PaymentPushedEvents] 가 장부에 옮긴다.
 */
interface PaymentPushGateway : PaymentWebhookGateway {
    /** 저장된 원문을 결제사 중립의 사건으로 읽는다. 장부가 다루지 않는 사건이면 null(줄은 `IGNORED`). 읽을 수 없는 원문은 던진다. */
    fun interpret(payload: String): PushedPaymentEvent?
}

object PushedEvents {
    /** 수신함 줄의 `resource_type` — 서명된 본문이 곧 사실이다. */
    const val RESOURCE_TYPE = "PUSHED"

    /** 구독이 우리 줄을 가리키는 `custom_data` 키 — 결제창을 열 때 서버가 정해 준다. */
    const val SUBSCRIPTION_KEY = "subscriptionId"
    const val ACCOUNT_KEY = "accountId"
    const val ORDER_KEY = "orderId"
}

sealed interface PushedPaymentEvent {
    val eventId: String
    val occurredAt: Instant
}

/** 결제 한 건이 끝났다. 금액 · 통화는 결제사가 말한 그대로다(여러 통화). [ourOrderId] 가 있으면 단건 주문, 없으면 구독 결제다. */
data class PushedPayment(
    override val eventId: String,
    override val occurredAt: Instant,
    val providerPaymentId: String,
    val total: PaymentAmount,
    val tax: Long?,
    val paidAt: Instant,
    val ourOrderId: String? = null,
    val ourAccountId: String? = null,
    val ourSubscriptionId: String? = null,
    val providerSubscriptionId: String? = null,
    val method: String? = null,
    val card: PaymentCard? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant? = null,
) : PushedPaymentEvent

enum class PushedSubscriptionStatus { ACTIVE, PAST_DUE, CANCELING, ENDED }

/** 결제사가 아는 구독의 지금 모습. 사건 순서가 뒤바뀌어도 [occurredAt] 이 가장 새로운 것만 반영된다. */
data class PushedSubscription(
    override val eventId: String,
    override val occurredAt: Instant,
    val providerSubscriptionId: String,
    val status: PushedSubscriptionStatus,
    val ourAccountId: String? = null,
    val ourSubscriptionId: String? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant? = null,
) : PushedPaymentEvent

/** 결제사가 결제와 갱신을 직접 하는 구독(우리 갱신 작업은 건너뛴다). 결제창은 프론트가 연다. */
interface PaymentHostedSubscriptionGateway : PaymentGateway {
    /** 결제창을 여는 값(공개해도 되는 값만). 이 플랜의 가격이 없거나 설정이 모자라면 null. */
    fun subscriptionSetup(request: HostedSubscriptionRequest): BillingSetup?

    /** 결제사에 해지를 요청한다. [atPeriodEnd] 가 false 면 즉시. 결과는 웹훅으로 장부에 닿는다. 못 하면 던진다. */
    fun cancel(providerSubscriptionId: String, atPeriodEnd: Boolean)
}

data class HostedSubscriptionRequest(
    val planCode: String,
    val accountId: String,
    val subscriptionId: String,
    val customerKey: String,
    val country: String?,
    /** 서버가 결제사에 미리 만든 첫 결제 거래 id - 결제창은 이것만 연다(가격 · 사용자 값을 브라우저가 넘기지 않는다). */
    val transactionId: String? = null,
)

/**
 * 서버가 결제사에 **거래를 먼저 만든다**(품목 = 서버가 정한 가격 · 수량, `custom_data` = 우리 줄 id). 결제창은 거래 id 만 열고, 웹훅은 우리 줄에 저장된 거래 id 로 맞춘다 -
 * 브라우저가 가격이나 어느 줄인지를 정하지 못한다. 만들지 못하면 던진다(주문 · 구독 시작도 실패하고 줄은 남지 않는다).
 */
interface PaymentTransactionGateway : PaymentGateway {
    /** @return 결제사의 거래 id */
    fun createTransaction(request: TransactionRequest): String
}

/** [itemRef] 는 단건 상품 코드 또는 구독 플랜 코드 - 결제사 가격은 결제사 모듈의 설정이 정한다. */
data class TransactionRequest(val itemRef: String, val quantity: Int, val customData: Map<String, String>)
