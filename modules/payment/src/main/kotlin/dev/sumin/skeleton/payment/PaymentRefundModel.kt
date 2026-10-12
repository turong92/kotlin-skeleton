package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentRefundStatus.FAILED
import dev.sumin.skeleton.payment.PaymentRefundStatus.PENDING
import dev.sumin.skeleton.payment.PaymentRefundStatus.REJECTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.REQUESTED
import dev.sumin.skeleton.payment.PaymentRefundStatus.SUCCEEDED
import dev.sumin.skeleton.payment.PaymentRefundStatus.WITHDRAWN

/**
 * 환불 사유 코드(`reason_code`, 스펙 §5.3) — **자유 문장은 받지 않는다.** 본인은 앞의 셋만 고르고([forCustomer]), 운영자는 거기에 [OPERATOR] 를 더해 쓴다([forOperator]).
 * [PROVIDER_CONSOLE] 은 결제사 관리 화면의 취소를 장부에 맞춰 넣은 줄에만, [SUBSCRIPTION_CANCEL] 은 구독 중도 해지의 환불 줄에만 우리가 붙인다.
 *
 * [providerText] 는 결제사에 보내는 취소 사유다(토스는 필수 · 최대 200자) — 사용자가 쓴 글이 아니라 **우리가 정한 고정 문장**이다.
 */
enum class PaymentRefundReason(val forCustomer: Boolean, val forOperator: Boolean, val providerText: String) {
    CUSTOMER_REQUEST(true, true, "Customer request"),
    DUPLICATE(true, true, "Duplicate payment"),
    NOT_DELIVERED(true, true, "Not delivered"),
    OPERATOR(false, true, "Operator refund"),
    SUBSCRIPTION_CANCEL(false, false, "Subscription canceled"),
    PROVIDER_CONSOLE(false, false, "Canceled in the provider console"),
    ;

    companion object {
        /** 코드 그대로(대소문자 구분)일 때만. 모르는 값은 null. */
        fun parse(code: String): PaymentRefundReason? = entries.firstOrNull { it.name == code }
    }
}

/**
 * `REFUND_DECLINED` 응답의 `data.category`(계약) — 결제사 코드는 `data.providerCode` 로 따로 나간다.
 * 동기 응답에서 확정 거절로 나오는 것은 결제사 모듈이 정한다([PaymentRefundGateway.refundDeclineCategory]). `ALREADY_REFUNDED` · `AMOUNT_EXCEEDED` 는
 * 결제사의 **모름** 코드(앞선 요청의 결과일 수 있다)라 동기 응답이 아니라 조회 정산 결과로만 나온다 — 그래서 기본 분류는 [OTHER] 다.
 */
enum class RefundDeclineCategory { ALREADY_REFUNDED, AMOUNT_EXCEEDED, PARTIAL_NOT_ALLOWED, PERIOD_EXPIRED, TEMPORARY, OTHER }

/**
 * 환불 줄 상태 전이표(스펙 §5.3) — 환불 줄의 상태를 바꾸는 코드는 모두 이것을 거친다([PaymentLedgerWriter]). 표에 없는 전이는 버그이지 업무 상황이 아니므로 [IllegalStateException] 이다.
 * 사용자 입력이 표 밖의 전이를 부르면 서비스 입구가 먼저 [PaymentRefundNotAllowedException] 으로 거절한다 — 이 예외는 서비스 안쪽 버그에서만 나온다.
 */
object PaymentRefundTransitions {
    private val allowed: Map<PaymentRefundStatus, Set<PaymentRefundStatus>> = mapOf(
        REQUESTED to setOf(PENDING, REJECTED, WITHDRAWN),
        PENDING to setOf(SUCCEEDED, FAILED, PENDING),
        SUCCEEDED to emptySet(),
        FAILED to emptySet(),
        REJECTED to emptySet(),
        WITHDRAWN to emptySet(),
    )

    /** 줄이 처음 생길 때의 상태: 본인의 요청 · 운영자의 바로 환불 · 결제사 쪽에서 일어난 취소를 맞춰 넣은 줄. */
    private val creatable = setOf(REQUESTED, PENDING, SUCCEEDED)

    fun isAllowed(from: PaymentRefundStatus, to: PaymentRefundStatus): Boolean = to in allowed.getValue(from)

    fun require(from: PaymentRefundStatus, to: PaymentRefundStatus) {
        check(isAllowed(from, to)) { "Refund status transition $from -> $to is not allowed" }
    }

    fun isCreatable(status: PaymentRefundStatus): Boolean = status in creatable

    fun requireCreatable(status: PaymentRefundStatus) {
        check(isCreatable(status)) { "A refund line cannot be created as $status" }
    }

    /** 열려 있는 동안(요청됨 · 환불 중) 주문당 하나뿐인 자리를 차지한다. */
    fun isOpen(status: PaymentRefundStatus): Boolean = status == REQUESTED || status == PENDING

    /** `open_key` — 열려 있는 동안 = 주문 id, 닫히면 null. 유니크라서 주문당 열린 환불은 하나다. */
    fun openKeyFor(status: PaymentRefundStatus, orderId: String): String? = orderId.takeIf { isOpen(status) }
}

/**
 * 주문 응답용 환불 요약. [openRefundStatus] 는 열린 환불이 있을 때만(`REQUESTED` = 본인이 요청함, `PENDING` = 환불 중), [refundPending] 은 열린 환불이 있다는 사실(둘 중 어느 쪽이든).
 * [refundableAmount] = 결제된 총액 - 성공한 환불의 합 - **`PENDING`(환불 중)인 열린 환불의 금액**(결제됨 · 일부 환불됨일 때만, 아니면 0).
 * `REQUESTED`(본인이 요청만 한 상태)는 줄이지 않는다 — 돈이 움직이지 않았고, 승인은 그 요청의 금액으로 실행된다.
 */
data class PaymentRefundSummary(
    val openRefundStatus: PaymentRefundStatus?,
    val refundPending: Boolean,
    val refundableAmount: Long,
) {
    companion object {
        fun of(order: PaymentOrder, refunds: List<PaymentRefund>): PaymentRefundSummary {
            val open = refunds.firstOrNull { PaymentRefundTransitions.isOpen(it.status) }
            return PaymentRefundSummary(open?.status, open != null, refundableOf(order, refunds))
        }

        /** 환불할 수 있는 상태(결제됨 · 일부 환불됨)일 때만. */
        fun refundableOf(order: PaymentOrder, refunds: List<PaymentRefund>): Long {
            if (order.status != PaymentOrderStatus.PAID && order.status != PaymentOrderStatus.PARTIALLY_REFUNDED) return 0
            val inFlight = refunds.filter { it.status == PaymentRefundStatus.PENDING }.sumOf { it.amount.amount }
            return ((order.paidAmount ?: order.amount.amount) - order.refundedAmount - inFlight).coerceAtLeast(0)
        }
    }
}

/** 한 주문의 환불 읽기 모델 — 주문 · 환불 줄(오래된 순) · 요약. */
data class OrderRefunds(
    val order: PaymentOrder,
    val refunds: List<PaymentRefund>,
    val summary: PaymentRefundSummary,
)

/** 환불 실행의 결과. [refund] 가 `SUCCEEDED` 면 끝(HTTP 200), `PENDING` 이면 결과를 모름(HTTP 202 — 조회 작업이 맞춘다). */
data class RefundOutcome(
    val refund: PaymentRefund,
    val order: PaymentOrder,
    /** 같은 `Idempotency-Key` 의 앞선 요청이 만든 줄을 그대로 돌려줬다. */
    val replayed: Boolean = false,
)

/** 본인의 환불 요청을 만든 결과. */
data class RequestedRefund(val refund: PaymentRefund, val replayed: Boolean)
