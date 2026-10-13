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

    /** 구독 중도 해지의 환불을 결제사에 보내지 못했거나 결제사가 거절했다 - 구독은 이미 끝났다. 운영자가 환불한다. */
    SUBSCRIPTION_CANCEL_REFUND_UNRESOLVED(true),

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
) {
    override fun toString(): String =
        "PaymentOrder(id=$id, status=$status, amount=${amount.amount} ${amount.normalizedCurrency}, refunded=$refundedAmount, " +
            "card=${if (cardIssuer == null && cardLast4 == null) "none" else "masked"})"
}

enum class PaymentRefundStatus { REQUESTED, PENDING, SUCCEEDED, FAILED, REJECTED, WITHDRAWN }

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
