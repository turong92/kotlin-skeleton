package dev.sumin.skeleton.payment

import java.time.Instant

/** 지금 끼운 키가 테스트용인가 실결제용인가. 결제사가 알 수 없으면 [UNKNOWN]. */
enum class PaymentMode {
    TEST,
    LIVE,
    UNKNOWN,
}

/**
 * 라우터가 아는 결제사의 공통 뿌리. 능력은 아래 인터페이스를 더해 선언한다.
 * **한 [providerId] 의 능력은 한 빈에 모여야 한다** — 라우터는 id 당 빈 하나만 받는다(같은 id 의 두 빈은 기동 실패).
 */
interface PaymentGateway {
    val providerId: String
    val supportedCurrencies: Set<String>
        get() = emptySet()
    val supportedCountries: Set<String>
        get() = emptySet()

    /** 주문 줄에 박고 배포 가드가 본다. */
    val mode: PaymentMode
        get() = PaymentMode.UNKNOWN

    /**
     * 이 통화로 결제사가 **받을 수 있는 가장 작은 금액**(최소 단위) - 모르면 null(제한 없음으로 본다). 할인 뒤 금액이 0 초과 이 값 미만이면 주문을 만들지 않고
     * `PAYMENT.DISCOUNT_BELOW_MINIMUM` 으로 거절한다(결제창이 열린 뒤에야 결제사가 거절하는 일이 없게; 구독은 시작 때 모든 할인 단계의 금액을 이 값과 견준다). 설정만 보고 답한다 - 외부 호출 없음.
     */
    fun minimumAmount(currency: String): Long? = null
}

/** 브라우저가 받아 온 결제 키를 서버가 승인한다. */
interface PaymentConfirmGateway : PaymentGateway {
    fun confirm(request: PaymentConfirmRequest): PaymentOperationResult

    /**
     * 이 결제 키를 결제사가 받을 수 있는 **모양**인가. 장부는 [confirm] 을 부르기 전 선점(커밋)에서 이것을 먼저 보고, false 면 선점 없이 400 으로
     * 거절한다 — 모양이 틀린 키가 선점된 뒤에야 [confirm] 이 거절하면 주문이 `CONFIRMING` 에 갇혀 조회 작업이 같은 키로 끝까지 실패한다.
     * 기본은 항상 true(모양을 따로 아는 결제사만 겹쳐 쓴다). 모양만 본다 — 결제가 있는지는 결제사가 답한다.
     *
     * **주문 줄 락 안에서 불린다 — 외부 호출 · I/O 를 하지 않는다**(문자열 검사만). 락을 쥔 채 기다리면 그 주문의 승인 · 환불 · 웹훅 수신이 함께 기다린다.
     */
    fun acceptsPaymentKey(providerPaymentId: String): Boolean = true

    /**
     * 돌아온 결제창의 승인 토큰(카카오페이 `pg_token`)이 결제사가 받을 수 있는 **모양**인가. [acceptsPaymentKey] 와 같은 자리(선점 전, 주문 줄 락 안 - 문자열 검사만)에서 불려
     * false 면 선점 없이 400 으로 거절한다 - 선점 뒤에 결제사가 거절하면 「모름」으로 읽혀 주문이 `CONFIRMING` 에 굳는다. 토큰이 없는 결제창(토스)은 늘 받는다(기본 true).
     * 모양만 본다 - 토큰이 진짜인지는 결제사가 답한다.
     */
    fun acceptsApprovalToken(approvalToken: String?): Boolean = true

    /**
     * 결제사가 **확정 거절**한 승인의 코드를 `DECLINED` 응답의 `data.category`(화면이 문구를 고르는 분류)로 옮긴다 — 결제사 코드 어휘는 결제사 모듈만 안다([PaymentRefundGateway.refundDeclineCategory] 와 같은 방식).
     * 기본은 [ConfirmDeclineCategory.OTHER]. 코드는 로그 · 참조 번호로만 나가고 화면 문구는 분류로 고른다.
     */
    fun confirmDeclineCategory(providerCode: String?): ConfirmDeclineCategory = ConfirmDeclineCategory.OTHER
}

/**
 * `DECLINED` 응답의 `data.category`(계약) — 사용자 화면이 거절 문구를 고르는 분류. 결제사 코드는 `data.providerCode` 로 따로 나간다.
 * [LIMIT_OR_BALANCE] 한도 · 잔액 / [CARD_DECLINED] 카드사 거절 / [TEMPORARY] 일시 장애(다시 해 볼 만함) / [OTHER] 그 밖.
 */
enum class ConfirmDeclineCategory { LIMIT_OR_BALANCE, CARD_DECLINED, TEMPORARY, OTHER }

/** 전액 · 부분 환불. 결과의 [PaymentOperationResult.remaining] 은 환불 뒤 결제사에 남은 금액. */
interface PaymentRefundGateway : PaymentGateway {
    fun refund(request: PaymentRefundRequest): PaymentOperationResult

    /**
     * 결제사가 **확정 거절**한 환불의 코드를 `REFUND_DECLINED` 의 분류로 옮긴다(결제사 코드 어휘는 결제사 모듈만 안다). 기본은 [RefundDeclineCategory.OTHER].
     * 확정 거절은 "돈이 움직이지 않았다"가 문서로 분명한 코드뿐이라 일시 오류([RefundDeclineCategory.TEMPORARY])나 앞선 요청의 결과일 수 있는
     * `ALREADY_REFUNDED` · `AMOUNT_EXCEEDED` 는 보통 여기로 오지 않는다 — 그런 코드는 모름이고 조회 정산이 가린다.
     */
    fun refundDeclineCategory(providerCode: String?): RefundDeclineCategory = RefundDeclineCategory.OTHER
}

/** 결제사가 아는 지금 상태. 결제가 없으면 null. */
interface PaymentLookupGateway : PaymentGateway {
    fun lookup(query: PaymentLookupQuery): PaymentSnapshot?

    /**
     * 주문 번호([PaymentLookupQuery.orderId])만으로 결제를 찾을 수 있는가. 거래 번호로만 조회되는 결제사(Paddle)는 false - 주문 번호만 든 조회는 결제사를 부르지 않고 「결제 없음」(null)이다.
     * 만료 작업은 false 인 결제사의 `PENDING` 을 조회 없이 마감 뒤 닫는다(조회 능력이 없는 결제사와 같은 길). 기본 true.
     */
    val answersByOrderId: Boolean
        get() = true
}

/**
 * 결제창을 여는 데 프론트가 필요한 것(공개해도 되는 값만).
 *
 * **계약**: 결제창 결제사는 **서버 승인 없이 돈을 움직이지 않는다** — 결제창이 끝나도 우리 서버가 승인을 부르기 전에는 결제가 끝나지 않는다.
 * 그래서 승인을 선점하지 않은 `PENDING` 은 돈이 움직였을 수 없고, 모드가 다르거나 결제사 빈이 없는 `PENDING` 을 결제사에 묻지 않고 마감 뒤 닫는 규칙(`OrderExpireTaskHandler`)이 이것에 기댄다.
 * 서버 승인 없이 결제가 끝나는 결제사(결제창이 곧 결제인 방식)를 붙이려면 그 규칙을 먼저 고친다.
 */
interface PaymentCheckoutGateway : PaymentGateway {
    fun checkout(order: CheckoutOrder): CheckoutInstruction

    /**
     * 지금 결제창에 넘길 값을 **낼 수 있는가** — `GET /payments/config` 의 `available` 이 이것을 묻는다(결제창이 열리지 않을 설정으로 "결제 가능"이라 답하지 않게).
     * 기본은 항상 true. 공개 키가 비었거나 시크릿과 모드가 다르면 값을 못 내는 결제사가 false 로 겹쳐 쓴다. 설정만 보고 답한다 — 외부 호출 없음.
     */
    fun canOpenCheckout(): Boolean = true
}

/** 웹훅 한 건의 진위를 확인하고 "어느 자원의 어떤 일"인지만 꺼낸다. 진위가 틀리거나 읽을 수 없으면 [PaymentWebhookRejectedException] 을 던진다. */
interface PaymentWebhookGateway : PaymentGateway {
    /** false 면 주소의 비밀 조각이 필수다. */
    val signed: Boolean

    fun parse(delivery: WebhookDelivery): WebhookEnvelope
}

/** 둘 중 하나는 있어야 한다. */
data class PaymentLookupQuery(
    val providerPaymentId: String?,
    val orderId: String?,
    /** 정기 결제(구독 주문)의 조회다 - 정기용 가맹점 코드 · 인증값으로 묻는 결제사(카카오페이 · 네이버페이)가 쓴다. 장부가 `origin=SUBSCRIPTION` 인 주문에 true 로 넘긴다. 나머지는 무시한다. */
    val recurring: Boolean = false,
) {
    init {
        require(!providerPaymentId.isNullOrBlank() || !orderId.isNullOrBlank()) {
            "A lookup needs a provider payment id or an order id"
        }
    }
}

/** 주문 하나를 묻는 조회 - 결제 키가 있으면 키로, 없으면 주문 번호로. 구독 주문이면 [PaymentLookupQuery.recurring]. */
internal fun PaymentOrder.lookupQuery(): PaymentLookupQuery =
    PaymentLookupQuery(providerPaymentId = providerPaymentId, orderId = if (providerPaymentId == null) id else null, recurring = originType == PaymentOriginType.SUBSCRIPTION)

/**
 * 이 주문에 대해 조회가 `null` 을 주면 「결제사에 그 결제가 없다」는 뜻인가. 주문 번호로 물을 수 없는 결제사(카카오페이)는 결제 키가 없으면 아니다 -
 * 응답이 유실된 승인이 돈을 받았을 수 있으니 새로 과금하거나 닫지 말고 사람에게 넘긴다. 조회 작업과 운영자 닫기가 같은 판단을 쓴다.
 */
internal fun PaymentLookupGateway.nullMeansNoPayment(order: PaymentOrder): Boolean = answersByOrderId || order.providerPaymentId != null

enum class ProviderPaymentStatus {
    IN_PROGRESS,
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    EXPIRED,
}

data class ProviderRefund(
    val providerRefundId: String,
    val amount: PaymentAmount,
    val at: Instant,
    /** 구매자의 이의 제기로 결제사가 가져간 돈(차지백)이다 - 장부는 사유 `CHARGEBACK` 줄로 넣는다. */
    val chargeback: Boolean = false,
    /** 차지백이 될 수 있는 이의의 **경고**다 - 이어서 같은 금액의 차지백이 와도 같은 이의라 두 번 세지 않는다. */
    val warning: Boolean = false,
)

/**
 * @property issuer 결제사 중립의 대문자 영문 카드사 코드(예 HYUNDAI). 모듈의 대응표에 없으면 결제사가 준 코드 그대로.
 * @property last4 가려진 번호의 마지막 네 글자(숫자 또는 `*` 만). **형식이 틀리면 null** 로 떨어진다(결제 흐름을 깨지 않는다).
 * 개인정보라 [toString] 은 값을 찍지 않는다.
 */
@ConsistentCopyVisibility
data class PaymentCard private constructor(
    val issuer: String?,
    val last4: String?,
) {
    override fun toString(): String = "PaymentCard(issuer=${if (issuer == null) "null" else "set"}, last4=${if (last4 == null) "null" else "masked"})"

    companion object {
        private val LAST4 = Regex("[0-9*]{1,4}")

        operator fun invoke(
            issuer: String?,
            last4: String?,
        ): PaymentCard = PaymentCard(issuer, last4?.takeIf { LAST4.matches(it) })
    }
}

data class PaymentSnapshot(
    val providerPaymentId: String,
    val orderId: String?,
    val status: ProviderPaymentStatus,
    val total: PaymentAmount,
    val remaining: PaymentAmount,
    val method: String?,
    val paidAt: Instant?,
    /** 결제사가 아는 취소 전부(이뤄진 것). */
    val refunds: List<ProviderRefund>,
    /** 카드 결제일 때만. */
    val card: PaymentCard? = null,
    /** 간편결제일 때 결제사 중립 브랜드 코드(`KAKAOPAY` …) - [LedgerValues.easyPayBrand] 가 거른다. */
    val easyPayBrand: String? = null,
    /** 결제사가 **접수해 검토 중**으로 쥔 환불(Paddle `pending_approval`) - 아직 이뤄지지 않았으니 [refunds] 에 없고, 정산이 그 환불 줄을 「적용 안 됨」으로 닫지 않게 한다. */
    val pendingRefunds: List<ProviderRefund> = emptyList(),
)

/** 웹훅 요청 그대로. [body] 는 서명 확인을 위한 원문 바이트다. */
class WebhookDelivery(
    headers: Map<String, String>,
    val body: ByteArray,
) {
    /** 헤더 이름은 소문자로 바꿔 담는다(같은 이름이 둘이면 뒤의 것). */
    val headers: Map<String, String> = headers.mapKeys { (name, _) -> name.lowercase() }
}

data class WebhookEnvelope(
    val eventKey: String,
    val eventType: String,
    val resourceType: String,
    val resourceId: String,
    val orderId: String? = null,
    /** 처리에 원문이 꼭 필요한 결제사만. 원문이라 [toString] 에 찍지 않는다. */
    val storedPayload: String? = null,
) {
    override fun toString(): String =
        "WebhookEnvelope(eventKey=$eventKey, eventType=$eventType, resourceType=$resourceType, resourceId=$resourceId, orderId=$orderId, storedPayload=${if (storedPayload == null) "null" else "<redacted>"})"
}

data class CheckoutOrder(
    val orderId: String,
    val orderName: String,
    val amount: PaymentAmount,
    /** 결제사가 가격을 직접 아는 결제사(Paddle)가 가격을 고르는 열쇠. */
    val itemRef: String? = null,
    val accountId: String? = null,
    /** 서버가 결제사에 미리 만든 거래 id(거래를 먼저 만드는 결제사) - 결제창은 이것만 연다. */
    val providerTransactionId: String? = null,
    /** 결제를 시작한 화면의 origin(`scheme://host[:port]`, 브라우저가 보낸 값). 결제사가 구매자를 돌려보낼 주소를 이 origin 으로 맞출 때만 쓴다([ReturnOrigins]) - 신뢰하지 않는 입력이라 허용 목록에 없으면 버려진다. */
    val returnOrigin: String? = null,
)

/** `checkout` 응답의 결제사 몫. 프론트가 [kind] 로 갈라 결제창을 연다. 공개해도 되는 값만 담는다. */
data class CheckoutInstruction(
    val provider: String,
    val kind: String,
    val testMode: Boolean,
    val orderId: String,
    val orderName: String,
    val amount: PaymentAmount,
    val clientKey: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    /**
     * 결제창을 열 때 **결제사가 먼저 정한 거래 번호**(카카오페이 `ready` 의 `tid`). 장부가 주문의 결제 키 자리에 박아 두고, 돌아온 승인 요청이 다른 번호를 들고 오면 결제사를 부르지 않는다.
     * 번호를 승인 때 브라우저가 들고 오는 결제창(토스 `paymentKey`)은 비운다.
     */
    val providerPaymentId: String? = null,
)
