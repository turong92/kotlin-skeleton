package dev.sumin.skeleton.payment

import java.time.Instant

/**
 * 결제사가 **서명한 웹훅 본문이 곧 사실**인 결제사(Paddle 같은 판매 대행 결제사)의 계약. 토스처럼 웹훅은 신호일 뿐 사실은 조회로 얻는 결제사는 [PaymentWebhookGateway] 만 구현한다.
 * 이 결제사는 `parse` 가 [PushedEvents.RESOURCE_TYPE] 으로 봉투를 내고 `storedPayload` 에 서명을 통과한 원문을 담는다 — 처리 작업이 [interpret] 로 읽어 [PaymentPushedEvents] 가 장부에 옮긴다.
 */
interface PaymentPushGateway : PaymentWebhookGateway {
    /** 저장된 원문을 결제사 중립의 사건으로 읽는다. 장부가 다루지 않는 사건이면 null(줄은 `IGNORED`). 읽을 수 없는 원문은 던진다. */
    fun interpret(payload: String): PushedPaymentEvent?

    /** 이 상품(단건 `itemRef`) · 플랜 코드로 서버가 거래에 넣는 결제사 가격 id - 단건과 첫 구독 결제의 품목을 이것과 대조한다(콘솔에서 품목을 바꿔 결제하는 길을 막는다). 설정에 없으면 null. */
    fun priceIdOf(itemRef: String): String?

    /** [priceIdOf] 의 거꾸로 - 결제사 가격 id 가 가리키는 플랜 코드. 모르면 null(콘솔에서 만든 가격 등). */
    fun itemRefOf(priceId: String): String? = null
}

/** 거래의 품목 한 줄 - 결제사의 가격 id 와 수량. */
data class PushedItem(val priceId: String, val quantity: Int)

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
    /** 거래의 품목 - 비어 있으면(읽지 못했다) 서버가 정한 품목과 같다고 할 수 없다. */
    val items: List<PushedItem> = emptyList(),
    /** 결제사 가격의 결제 주기(ISO-8601, 예 `P1M`) - 구독 결제에서 우리 로컬 플랜 주기 대신 쓴다. 모르면 null. */
    val period: String? = null,
    /** 결제사의 고객 · 주소 id - 구매자 주소의 나라를 [PaymentBuyerCountryGateway] 로 물을 때 쓴다. 결제 본문은 나라를 싣지 않는다. */
    val customerId: String? = null,
    val addressId: String? = null,
    /** 크레딧(이전 변경의 남은 몫)으로 낸 몫 - [total] 에서 이만큼은 돈이 오가지 않았다. 없으면 null. */
    val credit: Long? = null,
    /** 구독 플랜을 바꾸면서 생긴 거래(Paddle `origin=subscription_update`)면 true - 갱신이 아니다. */
    val subscriptionChange: Boolean = false,
    /** 구매자 주소의 나라(ISO-3166 두 글자) - 처리 작업이 락 밖에서 물어 채운다. 못 물었으면 null. */
    val buyerCountry: String? = null,
) : PushedPaymentEvent

/** 결제 시도가 실패했다(카드 거절 등). 거래는 열려 있어 같은 거래로 다시 결제할 수 있다 - 장부는 상태를 바꾸지 않고 이력 한 줄만 남긴다. [errorCode] 는 결제사가 준 사유 코드(설명 문장은 싣지 않는다). */
data class PushedPaymentFailure(
    override val eventId: String,
    override val occurredAt: Instant,
    val providerPaymentId: String,
    val errorCode: String? = null,
    val ourOrderId: String? = null,
) : PushedPaymentEvent

/** 결제사가 말하는 환불 한 건의 종류. */
enum class PushedRefundKind {
    REFUND,

    /** 구매자의 이의 제기로 결제사가 돈을 가져갔다(Paddle `chargeback` · `chargeback_warning`) - 우리가 시킨 일이 아니다. */
    CHARGEBACK,

    /** 분쟁에서 이겨 가져갔던 돈이 돌아왔다(Paddle `chargeback_reverse` · `chargeback_warning_reverse`). 같은 거래 · 같은 금액의 차지백 줄과 짝지어진다. */
    CHARGEBACK_REVERSAL,
}

/** 결제사가 그 환불에 내린 결정 - 접수만 했다 · 이뤄졌다 · 거절했다 · (차지백이) 되돌려졌다. */
enum class PushedRefundStatus { PENDING_APPROVAL, APPROVED, REJECTED, REVERSED }

/**
 * 결제사가 환불에 대해 알려 왔다(Paddle `adjustment.created` · `adjustment.updated`). 접수 · 승인 · 거절이 모두 이 사건이고, 몇 번 와도 같은 결과다 -
 * 끝 상태가 된 줄은 다시 움직이지 않는다. [providerPaymentId] 는 환불이 걸린 거래, [providerRefundId] 는 그 환불의 번호다.
 * 차지백([PushedRefundKind.CHARGEBACK])은 `APPROVED`(결제사가 돈을 가져갔다) 또는 `REVERSED`(분쟁에서 이겨 되돌렸다 - 원래 번호가 온다)로 오고, [warning] 은 이의의 경고다.
 * 되돌림 사건([PushedRefundKind.CHARGEBACK_REVERSAL])은 되돌림 adjustment 의 번호로 `APPROVED` 가 온다.
 */
data class PushedRefund(
    override val eventId: String,
    override val occurredAt: Instant,
    val kind: PushedRefundKind,
    val providerRefundId: String,
    val providerPaymentId: String,
    val status: PushedRefundStatus,
    val amount: PaymentAmount,
    val warning: Boolean = false,
    /** 환불이 걸린 거래의 결제사 구독 id(구독 거래면). 결제 웹훅보다 먼저 온 갱신 거래의 환불이 우리 것인지 가리는 데만 쓴다. */
    val providerSubscriptionId: String? = null,
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
    /** 구독이 만들어진 사건(`subscription.created`)만 싣는, 구독을 만든 거래 id - 첫 결제 사건을 놓쳐도 줄에 결제사 구독 id 를 연결할 수 있다. */
    val transactionId: String? = null,
    /** 결제사 구독의 결제 주기(ISO-8601). 모르면 null - 줄의 주기를 그대로 둔다. */
    val period: String? = null,
    /** 구독의 품목 - 결제사 쪽에서 바뀐 플랜을 따라가는 데 쓴다. 읽지 못했으면 비어 있다. */
    val items: List<PushedItem> = emptyList(),
) : PushedPaymentEvent

/**
 * 결제 본문에 구매자 주소의 나라가 없는 결제사(Paddle)가 고객 · 주소 id 로 그 나라를 알려 준다. 처리 작업이 **락 · 트랜잭션 밖에서** 부르고, 실패해도 결제는 그대로 반영된다(나라만 모른다).
 */
interface PaymentBuyerCountryGateway : PaymentGateway {
    /** @return ISO-3166 alpha-2 대문자. 모르면 null, 호출이 실패하면 던진다. */
    fun buyerCountry(customerId: String, addressId: String): String?
}

/** 결제사가 결제와 갱신을 직접 하는 구독(우리 갱신 작업은 건너뛴다). 결제창은 프론트가 연다. */
interface PaymentHostedSubscriptionGateway : PaymentGateway {
    /** 결제창을 여는 값(공개해도 되는 값만). 이 플랜의 가격이 없거나 설정이 모자라면 null. */
    fun subscriptionSetup(request: HostedSubscriptionRequest): BillingSetup?

    /** 결제사에 해지를 요청한다. [atPeriodEnd] 가 false 면 즉시. 결과는 웹훅으로 장부에 닿는다. 못 하면 던진다. */
    fun cancel(providerSubscriptionId: String, atPeriodEnd: Boolean)
}

/**
 * 결제사가 들고 있는 구독의 플랜을 올린다(등급↑ · 주기↑). 판정은 우리([PlanChangeRules]), 청구 방식과 금액은 결제사가 정한다 - 내리기는 이 능력이 다루지 않는다.
 * 결제사 호출이므로 락 · 트랜잭션 밖에서 부른다. 결제가 거절되면 [PaymentDeclinedException], 결제사가 바꿀 수 없는 때면 [PaymentPlanChangeNotAllowedException],
 * 그 밖(시간 초과 · 5xx)은 던진다 - 모름이고 바뀌었다면 구독 웹훅이 장부를 맞춘다.
 */
interface PaymentHostedPlanChangeGateway : PaymentGateway {
    /** 적용하지 않고 견적만 낸다. */
    fun previewPlanChange(change: HostedPlanChange): HostedPlanChangeQuote

    /** 적용한다 - 결제가 필요하면 결제까지 끝낸 뒤 돌아온다. */
    fun applyPlanChange(change: HostedPlanChange): HostedPlanChangeQuote
}

/** [targetItemRef] 는 우리 플랜 코드 - 결제사 가격은 결제사 모듈이 정한다. */
data class HostedPlanChange(val providerSubscriptionId: String, val targetItemRef: String, val kind: PlanChangeKind)

/**
 * 결제사가 계산한 변경 견적(결제사의 통화). [charge] 는 지금 결제하는 금액(크레딧을 뺀 뒤), [credit] 은 쓴 크레딧, [recurring] 은 앞으로 갱신마다 내는 금액.
 * 기간은 변경 뒤의 결제사 기간이고, [restartsPeriod] 는 그 기간이 지금 줄의 기간과 다르게 새로 시작한다는 뜻이다.
 */
data class HostedPlanChangeQuote(
    val charge: PaymentAmount,
    val credit: PaymentAmount?,
    val recurring: PaymentAmount,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val restartsPeriod: Boolean,
)

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

/**
 * 결제사가 구매자 위치의 통화로 환산한 가격을 브라우저가 직접 물어볼 수 있게 하는 값(공개해도 되는 것만) - 화면이 우리 플랜 표의 금액 대신 **실제로 결제될 금액**을 보이게 한다.
 * 설정이 모자라면 null.
 */
interface PaymentPricePreviewGateway : PaymentGateway {
    fun pricePreview(): PricePreviewSetup?
}

/**
 * 결제창 안에서 바로 열 수 있는 간편결제 코드(결제사 모듈의 설정). 결제 화면이 주문을 만들기 **전에** 단추를 그리려고 `GET /config` 로 나간다 -
 * 같은 목록이 checkout 응답에도 실려 화면이 고른 코드를 거기서 한 번 더 맞춰 본다. 비면 단추는 카드 하나.
 */
interface PaymentEasyPayGateway : PaymentGateway {
    fun easyPays(): List<String>
}

/** [prices] 는 상품 코드 · 플랜 코드 → 결제사 가격 id (비밀이 아니다). */
data class PricePreviewSetup(val clientToken: String, val environment: String, val prices: Map<String, String>)
