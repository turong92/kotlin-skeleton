package dev.sumin.skeleton.payment

import java.time.Instant

/** 주문 상태 — 전이는 [PaymentOrderTransitions] 한 곳이 정한다(스펙 §5.2). */
enum class PaymentOrderStatus {
    PENDING,
    CONFIRMING,
    PAID,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED,
    EXPIRED,
    CANCELED,
    ;

    /** 돈이 들어온 적이 있는 상태(환불 이후 포함). */
    val isPaidFamily: Boolean get() = this == PAID || this == PARTIALLY_REFUNDED || this == REFUNDED

    /** 결제가 닫힌 상태 — 결제사 조회 증거로만 [PAID] 로 다시 열린다. */
    val isClosed: Boolean get() = this == FAILED || this == EXPIRED || this == CANCELED
}

/**
 * 주문의 정산 방식(파생값 - 새 칸이 아니라 [PaymentOrder.provider] 가 정한다). [FREE] 쿠폰으로 최종가가 0원이라 결제사 없이 끝났다 ·
 * [EXEMPT] 결제 면제 권한 계정이라 결제사 없이 끝났다(할인이 아니다). 둘 다 처음부터 `PAID` 이고 결제창이 없고 환불할 것이 없다.
 */
enum class PaymentSettlement {
    PROVIDER,
    FREE,
    EXEMPT,
    ;

    /** 결제사 모듈이 없는 정산 - 환불 · 결제사와 맞추기의 대상이 아니다. */
    val isOffProvider: Boolean get() = this != PROVIDER

    companion object {
        fun of(provider: String): PaymentSettlement = when (provider) {
            PaymentOrders.FREE_PROVIDER -> FREE
            PaymentOrders.EXEMPT_PROVIDER -> EXEMPT
            else -> PROVIDER
        }
    }
}

/** 주문에 붙은 할인 한 줄의 상태 - 예약(주문이 쥐고 있다) → 확정(결제가 끝나 썼다) / 해제(결제가 안 됐다) / 복원(전액 환불로 돌려줬다). */
enum class OrderDiscountStatus { RESERVED, CONFIRMED, RELEASED, RESTORED }

/**
 * 주문에 붙은 할인 내역 한 줄 - [kind] 는 할인 수단(`coupon`), [sourceRef] 는 그 수단 안의 식별(쿠폰 id), [amount] 는 깎인 금액(양수, 최소 단위).
 * 결제사 모듈이 아니라 장부의 것이다 - 금액은 서버가 계산한 값이고 브라우저가 보낸 값이 아니다.
 */
data class OrderDiscount(
    val orderId: String,
    val seq: Int,
    val kind: String,
    val sourceRef: String,
    val label: String,
    val amount: Long,
    val currency: String,
    val status: OrderDiscountStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class PaymentOrderKind { ONE_TIME, SUBSCRIPTION_INITIAL, SUBSCRIPTION_RENEWAL, SUBSCRIPTION_CHANGE }

/** 이 주문을 만든 것. 1단계는 [CHECKOUT] 뿐이다. */
enum class PaymentOriginType { CHECKOUT, SUBSCRIPTION }

/**
 * 사람이 봐야 하는 이유(`review_reason`). 칸이 하나라 **우선순위**가 있다: 돈이 어긋난 이유([isMoney])가 지급 쪽 이유([FULFILMENT_UNRESOLVED] · [REFUND_APPLY_UNRESOLVED])를 덮어쓰고
 * 반대는 안 된다 — 같은 급끼리는 먼저 찍힌 것이 남는다([overrides]). [ORDER_MISMATCH] 는 결제사가 다른 주문의 결제를 가리킬 때다.
 */
enum class PaymentReviewReason(val isMoney: Boolean) {
    AMOUNT_MISMATCH(true),
    ORDER_MISMATCH(true),
    REFUND_MISMATCH(true),
    MODE_MISMATCH(true),
    CONFIRM_UNRESOLVED(true),

    /** 구독의 지금 과금 주문이 아닌 구독 주문이 늦게 `PAID` 가 됐다 — 구독 기간은 그대로이고 돈만 받았다. 운영자가 환불한다. */
    SUBSCRIPTION_STALE_CHARGE(true),

    /** 첫 결제는 받았는데 결제사가 빌링키(SID)를 알려 주지 못했다 - 구독은 켜졌지만 갱신할 수단이 없어 다음 갱신일에 과금 없이 끝난다. 운영자가 알고 있게 하고, 필요하면 결제사 화면에서 확인한다. */
    SUBSCRIPTION_TOKEN_MISSING(false),

    /** 구독 중도 해지의 환불을 결제사에 보내지 못했거나 결제사가 거절했다 - 구독은 이미 끝났다. 운영자가 환불한다. */
    SUBSCRIPTION_CANCEL_REFUND_UNRESOLVED(true),

    /** 해외 결제사(Paddle)로 결제한 구매자의 주소 나라가 한국이다 - 한국 구매자는 토스 + 한국 정책이 맞다. 막지 않았고 돈은 받았다; 운영자가 본다. */
    BUYER_COUNTRY_KR(false),

    /**
     * 쿠폰 같은 할인 수단이 늦게 `PAID` 가 된 주문에 자기 것을 다시 내주지 못했다 - 그사이 다른 주문이 그 쿠폰을 쥐었다. 두 주문 모두 그대로 두고(손님은 이미 할인가로 냈다),
     * 이 주문에 표시만 한다. 지급은 평소처럼 나간다. 운영자가 판단한다.
     */
    DISCOUNT_CONFLICT(false),

    /** 지급 작업이 죽었다 — 돈은 받았는데 `fulfilled_at` 이 비어 있다. 운영자의 맞추기가 지급 작업을 다시 넣고 푼다. */
    FULFILMENT_UNRESOLVED(false),

    /** 회수 작업이 죽었다 — 환불 줄은 `SUCCEEDED` 인데 `applied_at` 이 비어 있다. 운영자의 맞추기가 회수 작업을 다시 넣고 푼다. */
    REFUND_APPLY_UNRESOLVED(false),
    ;

    /** [existing] 이 찍힌 칸을 이 이유가 덮어쓰는가 — 돈 쪽 이유가 지급 쪽 이유를 덮을 때만. */
    fun overrides(existing: PaymentReviewReason): Boolean = isMoney && !existing.isMoney
}

/**
 * 주문 장부의 한 줄(스펙 §5.1). 결제 한 번 = 한 줄이고, 줄의 상태가 돈의 사실이다.
 * 개인정보로 볼 수 있는 칸은 [cardIssuer] · [cardLast4] 둘뿐이라 [toString] 이 찍지 않는다.
 */
data class PaymentOrder(
    val id: String,
    val accountId: String,
    val kind: PaymentOrderKind,
    val status: PaymentOrderStatus,
    val provider: String,
    val providerMode: PaymentMode,
    val providerPaymentId: String?,
    /** 청구하려는 금액 · 통화. 만들 때 박히고 바뀌지 않는다. */
    val amount: PaymentAmount,
    /** 결제사가 실제로 받았다고 말한 총액. */
    val paidAmount: Long?,
    val taxAmount: Long?,
    val refundedAmount: Long,
    val itemType: String,
    val itemRef: String,
    val quantity: Int,
    val orderName: String,
    val originType: PaymentOriginType,
    val originRef: String?,
    val dedupeKey: String?,
    val method: String?,
    val cardIssuer: String?,
    val cardLast4: String?,
    val failureCode: String?,
    val reviewReason: PaymentReviewReason?,
    val checkoutExpiresAt: Instant,
    val confirmStartedAt: Instant?,
    val paidAt: Instant?,
    val fulfilledAt: Instant?,
    val closedAt: Instant?,
    val accountErasedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 서버가 주문을 만들 때 정한 접속 나라(ISO-3166 두 글자) - 화면이 보낸 값이 아니다. 모르면 null. */
    val country: String? = null,
    /** 간편결제의 브랜드 코드(`KAKAOPAY` · `NAVERPAY` · `TOSSPAY` …, 결제사 중립) - 간편결제가 아니거나 모르면 null(옛 주문 포함). 경로(어느 결제사로 갔나)는 [provider] 다. */
    val easyPayBrand: String? = null,
    /** 할인 전 금액(최소 단위). 할인도 면제도 없는 옛 줄 · 일반 주문은 null - 그때 [amount] 가 곧 정가다. 면제 주문은 카탈로그 가격(얼마어치를 면제했나). */
    val originalAmount: Long? = null,
    /** 깎인 금액의 합(최소 단위). 할인 내역 줄의 합과 같다 - 면제는 할인이 아니라 0. */
    val discountAmount: Long = 0,
) {
    /** 어떻게 정산됐나 - 결제사 id 가 예약 id(`free` · `exempt`)이면 결제사 없이 끝난 주문이다. */
    val settlement: PaymentSettlement get() = PaymentSettlement.of(provider)

    override fun toString(): String =
        "PaymentOrder(id=$id, status=$status, amount=${amount.amount} ${amount.normalizedCurrency}, refunded=$refundedAmount, " +
            "card=${if (cardIssuer == null && cardLast4 == null) "none" else "masked"})"
}

/**
 * [REVERSED] = 이뤄졌던 환불이 결제사 증거로 **되돌려졌다**(차지백 분쟁에서 이겨 돈이 돌아왔다 - Paddle). 끝 상태이고 주문의 환불된 금액에서 빠진다. 환불 줄은 양수만 받으므로(`ck_payment_refunds_amount`) 음수 줄 대신 원래 줄을 되돌린다.
 */
enum class PaymentRefundStatus { REQUESTED, PENDING, SUCCEEDED, FAILED, REJECTED, WITHDRAWN, REVERSED }

enum class PaymentRefundOrigin { USER, OPERATOR, PROVIDER }

/** 환불 한 건 = 한 줄(스펙 §5.3). 상태 전이는 [PaymentRefundTransitions] 한 곳이 정한다. 계정 id 를 담은 칸이 있어 [toString] 은 찍지 않는다. */
data class PaymentRefund(
    val id: String,
    val orderId: String,
    val status: PaymentRefundStatus,
    val origin: PaymentRefundOrigin,
    val amount: PaymentAmount,
    val reasonCode: String,
    /** 열려 있는 동안(REQUESTED · PENDING) = [orderId], 닫히면 null → 주문당 열린 환불은 하나. */
    val openKey: String?,
    val dedupeKey: String?,
    val providerRefundId: String?,
    val expectedRemaining: Long?,
    val requestedBy: String?,
    val decidedBy: String?,
    val failureCode: String?,
    val requestedAt: Instant,
    val sentAt: Instant?,
    val completedAt: Instant?,
    val appliedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 운영자가 금액을 고쳐 승인했다면 처음 요청한 금액(그때 [amount] 는 승인한 금액), 아니면 null. */
    val requestedAmount: Long? = null,
    /** 운영자가 「구독도 해지」를 끄고 보낸 환불 - 이 줄이 주문을 전액 환불로 만들어도 구독을 끝내지 않는다. 환불이 끝나는 때(결제사는 며칠 뒤)에 적용되므로 줄에 남는다. */
    val keepSubscription: Boolean = false,
) {
    override fun toString(): String =
        "PaymentRefund(id=$id, orderId=$orderId, status=$status, origin=$origin, amount=${amount.amount} ${amount.normalizedCurrency}, reason=$reasonCode)"
}

/**
 * 수신함 줄의 상태(스펙 §5.4). `RECEIVED`(받았다 · 처리 작업이 기다린다) → `PROCESSED`(조회해서 맞췄다) / `IGNORED`(맞출 것이 없었다) 가 끝이고,
 * `FAILED`(이번 시도가 실패 — 작업이 다시 한다) · `DEAD`(작업이 시도를 다 썼다 — 결제사의 재전송이나 운영자의 다시 처리가 새 작업을 넣는다) 는 끝나지 않은 줄이다.
 */
enum class PaymentWebhookStatus {
    RECEIVED,
    PROCESSED,
    IGNORED,
    FAILED,
    DEAD,
    ;

    /** 더 할 일이 없는 줄 — 결제사가 같은 이벤트를 다시 보내도 새 작업을 넣지 않는다. */
    val isFinished: Boolean get() = this == PROCESSED || this == IGNORED
}

/** 웹훅 수신함의 한 줄(스펙 §5.4) — 받은 사실과 처리 결과. 자유 문장 열이 없다(`lastError` 는 우리 코드). */
data class PaymentWebhookEvent(
    val id: Long,
    val provider: String,
    val eventKey: String,
    val eventType: String,
    val resourceType: String,
    val resourceId: String,
    val orderId: String?,
    val payload: String?,
    val payloadHash: String,
    val status: PaymentWebhookStatus,
    val deliveries: Int,
    val lastError: String?,
    val receivedAt: Instant,
    val processedAt: Instant?,
) {
    override fun toString(): String =
        "PaymentWebhookEvent(id=$id, provider=$provider, eventKey=$eventKey, eventType=$eventType, resourceType=$resourceType, status=$status, deliveries=$deliveries)"
}

enum class PaymentEventType {
    ORDER_CREATED,
    CONFIRM_STARTED,
    CONFIRM_SUCCEEDED,
    CONFIRM_DECLINED,
    CONFIRM_UNKNOWN,
    CONVERGED,
    EXPIRED,
    CANCELED,
    REOPENED_PAID,
    FULFILLED,
    REFUND_REQUESTED,
    REFUND_SENT,
    REFUND_SUCCEEDED,
    REFUND_FAILED,
    REFUND_UNKNOWN,
    REFUND_REJECTED,
    REFUND_WITHDRAWN,
    REFUND_SYNCED,

    /** 앱의 `onRefunded` 가 끝났다(지급의 [FULFILLED] 에 짝). */
    REFUND_APPLIED,
    REVIEW_FLAGGED,

    /** 결제사가 거래 만들기를 거절했다(주문은 거래 id 없이 남는다) - [PaymentEvent.providerCode] 는 `상태:결제사코드` 또는 `HTTP_상태`. */
    TRANSACTION_FAILED,

    /** 결제 시도가 실패했다(결제사 웹훅, 카드 거절 등) - 주문 상태는 그대로이고 [PaymentEvent.providerCode] 는 결제사의 사유 코드. */
    PAYMENT_FAILED,

    /** 차지백 분쟁에서 이겨 돈이 돌아왔다 - 환불 줄이 `SUCCEEDED` → `REVERSED`(환불 줄의 이력). 지급 · 구독은 되살리지 않는다. */
    CHARGEBACK_REVERSED,

    /** 결제 면제 권한(`payment.exempt`)을 가진 계정의 주문이라 결제사 없이 완료됐다 - 행위자는 그 계정이다. 이어서 `PENDING → PAID` 이력이 남는다. */
    EXEMPTED,
}

enum class PaymentActor { USER, OPERATOR, SYSTEM, PROVIDER }

/**
 * 추가만 하는 이력(스펙 §5.4) — "이 주문이 왜 이 상태인가"를 로그 없이 답한다. 자유 문장 열이 없다.
 * [providerCode] 는 결제사의 오류 · 상태 코드다. 열이 하나뿐이라 **우리 코드도 여기 들어간다**: [PaymentEventType.REVIEW_FLAGGED] 에서는 검토 이유([PaymentReviewReason] 이름),
 * 환불에서는 `REFUND_LANDED`(주문이 환불을 받아들임, `CONVERGED`) · `REFUND_MISMATCH_CLEARED`(검토 표시가 풀림) · `NOT_APPLIED`(결제사에 적용되지 않았다고 닫음) ·
 * `PROVIDER_PENDING`(결제사가 환불을 접수했다고 답함) · `RESULT_MISMATCH`(결제사가 요청과 다른 금액을 취소했다고 답함) ·
 * `LATE_RESULT`(환불됨 응답이 도착했는데 줄이 이미 닫혀 있었음) · `ORDER_REFUNDED`(주문이 전액 환불돼 열린 요청을 닫음).
 * 결제사 코드와 이 코드를 가르려면 이력의 종류와 함께 읽는다.
 */
data class PaymentEvent(
    val orderId: String,
    val type: PaymentEventType,
    val actor: PaymentActor,
    val createdAt: Instant,
    val refundId: String? = null,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val actorId: String? = null,
    val providerCode: String? = null,
    val providerTraceId: String? = null,
    val traceId: String? = null,
)
