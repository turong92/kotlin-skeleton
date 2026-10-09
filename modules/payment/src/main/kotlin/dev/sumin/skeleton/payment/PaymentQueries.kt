package dev.sumin.skeleton.payment

import java.time.Instant

/** 주문 하나와 그 환불 줄(오래된 순) — 응답의 `refundableAmount` · `openRefundStatus` · `refundPending` 이 여기서 나온다. */
data class OrderView(val order: PaymentOrder, val refunds: List<PaymentRefund>) {
    val summary: PaymentRefundSummary get() = PaymentRefundSummary.of(order, refunds)
}

data class OrderPage(val values: List<OrderView>, val next: OrderCursor?)

/** 운영자가 보는 주문 — [reviewReason] 은 줄에 찍힌 이유가 있으면 그것, 없으면 [ReviewReasons] 가 오래됨 · 지급 · 회수로 파생한 것. */
data class OperatorOrderView(val order: PaymentOrder, val refunds: List<PaymentRefund>, val reviewReason: String?) {
    val summary: PaymentRefundSummary get() = PaymentRefundSummary.of(order, refunds)
}

data class OperatorOrderPage(val values: List<OperatorOrderView>, val next: OrderCursor?)

data class OperatorOrderDetail(val view: OperatorOrderView, val events: List<PaymentEvent>)

data class RefundPage(val values: List<PaymentRefund>, val next: RefundCursor?)

/** 운영자 주문 목록의 질문. [q] 는 주문 id 또는 결제사 결제 번호의 **정확한** 값이다. */
data class OperatorOrderQuery(
    val status: PaymentOrderStatus? = null,
    val accountId: String? = null,
    val q: String? = null,
    val review: Boolean = false,
    val includeErased: Boolean = false,
    val before: OrderCursor? = null,
    val size: Int = PaymentQueries.DEFAULT_PAGE,
)

/**
 * 「확인 필요」의 이유 아홉 가지(스펙 §5.1 + B3 의 `ORDER_MISMATCH` + 파생 둘). 줄에 찍힌 이유([PaymentReviewReason])가 있으면 **그것이 먼저**다.
 * 없을 때 줄과 환불 줄에서 파생한다 — 장부가 스스로 표시하지 못한 마지막 안전망(표시하는 고리가 같이 실패한 경우):
 *  - `STALE_CONFIRMING` — 승인을 선점한 뒤 [staleAfter] 보다 오래 `CONFIRMING`
 *  - `STALE_REFUND` — [staleAfter] 보다 오래 `PENDING` 인 환불 줄이 있다
 *  - `FULFILMENT_UNRESOLVED` — 돈은 받았는데(`PAID` · `PARTIALLY_REFUNDED`) [staleAfter] 가 지나도 지급 시각이 비었다 (죽은 지급 작업의 표시와 같은 뜻)
 *  - `REFUND_APPLY_UNRESOLVED` — 환불은 `SUCCEEDED` 인데 [staleAfter] 가 지나도 회수 시각이 비었다 (죽은 회수 작업의 표시와 같은 뜻)
 */
class ReviewReasons(private val staleAfter: java.time.Duration) {
    fun staleBefore(now: Instant): Instant = now.minus(staleAfter)

    fun of(order: PaymentOrder, refunds: List<PaymentRefund>, now: Instant): String? {
        order.reviewReason?.let { return it.name }
        val before = staleBefore(now)
        if (order.status == PaymentOrderStatus.CONFIRMING && order.confirmStartedAt?.isBefore(before) == true) return STALE_CONFIRMING
        if (refunds.any { it.status == PaymentRefundStatus.PENDING && it.sentAt?.isBefore(before) == true }) return STALE_REFUND
        if ((order.status == PaymentOrderStatus.PAID || order.status == PaymentOrderStatus.PARTIALLY_REFUNDED) && order.fulfilledAt == null &&
            (order.paidAt == null || order.paidAt.isBefore(before))
        ) {
            return PaymentReviewReason.FULFILMENT_UNRESOLVED.name
        }
        if (refunds.any { it.status == PaymentRefundStatus.SUCCEEDED && it.appliedAt == null && (it.completedAt == null || it.completedAt.isBefore(before)) }) {
            return PaymentReviewReason.REFUND_APPLY_UNRESOLVED.name
        }
        return null
    }

    companion object {
        const val STALE_CONFIRMING = "STALE_CONFIRMING"
        const val STALE_REFUND = "STALE_REFUND"
    }
}

/**
 * HTTP 가 쓰는 읽기 모델(목록 · 한 건). 잠그지 않는다 — 보여 주기용이다. 목록은 줄마다 묻지 않고 한 쪽의 환불 줄을 한 번에 읽는다.
 * 모든 목록은 최신순 커서이고 한 쪽은 1~[MAX_PAGE] 줄이다.
 */
class PaymentQueries(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val writer: PaymentLedgerWriter,
    properties: PaymentProperties,
) {
    private val reasons = ReviewReasons(properties.admin.staleAfter)

    /** 내 결제 내역 — 결제하지 않은 시도(`PENDING`)는 빠진다. */
    fun myOrders(accountId: String, before: OrderCursor?, size: Int): OrderPage {
        val limit = clamp(size)
        val rows = store.listAccountOrders(accountId, before, limit + 1)
        val page = rows.take(limit)
        val refunds = refundsOf(page)
        return OrderPage(page.map { OrderView(it, refunds[it.id].orEmpty()) }, nextOf(rows.size > limit, page.lastOrNull()))
    }

    fun operatorOrders(query: OperatorOrderQuery): OperatorOrderPage {
        val limit = clamp(query.size)
        val now = writer.now()
        val filter = OperatorOrderFilter(query.status, query.accountId, hideErased = !query.includeErased)
        if (!query.q.isNullOrBlank()) return exact(query.q.trim(), query, limit, now)
        val rows = if (query.review) reviewRows(filter.copy(hideErased = false), query.before, limit + 1, now) else store.listOrdersForOperator(OperatorOrderScope.ALL, filter, null, query.before, limit + 1)
        val page = rows.take(limit)
        val refunds = refundsOf(page)
        return OperatorOrderPage(page.map { OperatorOrderView(it, refunds[it.id].orEmpty(), reasons.of(it, refunds[it.id].orEmpty(), now)) }, nextOf(rows.size > limit, page.lastOrNull()))
    }

    /** 주문 id 또는 결제사 결제 번호의 정확한 값 — 한두 줄이라 커서가 없다. 결제 번호는 `provider in (아는 결제사)` 와 같이 찾아 유니크 인덱스를 탄다. */
    private fun exact(q: String, query: OperatorOrderQuery, limit: Int, now: Instant): OperatorOrderPage {
        val found = (listOfNotNull(store.findOrder(q)) + store.findOrdersByProviderPayment(router.providerIds(), q)).distinctBy { it.id }
            .filter { query.status == null || it.status == query.status }
            .filter { query.accountId == null || it.accountId == query.accountId }
        val refunds = refundsOf(found)
        val views = found.map { OperatorOrderView(it, refunds[it.id].orEmpty(), reasons.of(it, refunds[it.id].orEmpty(), now)) }
            .filter { !query.review || it.reviewReason != null }
            .sortedWith(compareByDescending<OperatorOrderView> { it.order.createdAt }.thenByDescending { it.order.id })
            .take(limit)
        return OperatorOrderPage(views, null)
    }

    /**
     * 「확인 필요」 = 줄에 이유가 찍힌 것 + 파생(오래 확인 중 · 오래 환불 중 · 지급 안 끝남 · 회수 안 끝남). 갈래마다 자기 인덱스로 한 번씩 묻고 최신순으로 합친다 —
     * 한 문장에 `or` 로 묶으면 어느 인덱스도 못 탄다. **환불 쪽 두 갈래도 같은 질의 모양**(주문 쪽 필터 · 커서 · 개수 제한이 SQL 에서 걸린다) — 묶인 줄이 아무리 많아도
     * 가장 오래된 줄이 잘리지 않고 `accountId` 필터가 자른 뒤에 걸리지 않는다. 갈래마다 최대 [limit] 줄을 읽고 합쳐서 [limit] 줄로 자르므로 커서로 끝까지 넘어간다.
     */
    private fun reviewRows(filter: OperatorOrderFilter, before: OrderCursor?, limit: Int, now: Instant): List<PaymentOrder> {
        val staleBefore = reasons.staleBefore(now)
        return listOf(
            store.listOrdersForOperator(OperatorOrderScope.REVIEW_REASON, filter, null, before, limit),
            store.listOrdersForOperator(OperatorOrderScope.STALE_CONFIRMING, filter, staleBefore, before, limit),
            store.listOrdersForOperator(OperatorOrderScope.UNFULFILLED, filter, staleBefore, before, limit),
            store.listOrdersForOperator(OperatorOrderScope.STALE_REFUND, filter, staleBefore, before, limit),
            store.listOrdersForOperator(OperatorOrderScope.UNAPPLIED_REFUND, filter, staleBefore, before, limit),
        ).flatten()
            .distinctBy { it.id }
            .sortedWith(compareByDescending<PaymentOrder> { it.createdAt }.thenByDescending { it.id })
            .take(limit)
    }

    /** 주문 한 건 + 환불 + 이력(오래된 순). 없으면 [PaymentOrderNotFoundException]. */
    fun operatorOrder(orderId: String): OperatorOrderDetail {
        val order = store.findOrder(orderId) ?: throw PaymentOrderNotFoundException()
        return OperatorOrderDetail(operatorView(order), store.listEvents(orderId))
    }

    fun operatorView(order: PaymentOrder): OperatorOrderView {
        val refunds = store.listRefunds(order.id)
        return OperatorOrderView(order, refunds, reasons.of(order, refunds, writer.now()))
    }

    /** 환불 줄 목록, 최신순. */
    fun operatorRefunds(status: PaymentRefundStatus, before: RefundCursor?, size: Int): RefundPage {
        val limit = clamp(size)
        val rows = store.listRefundsByStatus(status, before, limit + 1)
        val page = rows.take(limit)
        return RefundPage(page, if (rows.size > limit) page.lastOrNull()?.let { RefundCursor(it.requestedAt, it.id) } else null)
    }

    private fun refundsOf(orders: List<PaymentOrder>): Map<String, List<PaymentRefund>> =
        store.listRefundsOfOrders(orders.map { it.id }).groupBy { it.orderId }

    private fun nextOf(hasMore: Boolean, last: PaymentOrder?): OrderCursor? = if (hasMore) last?.let { OrderCursor(it.createdAt, it.id) } else null

    private fun clamp(size: Int) = size.coerceIn(1, MAX_PAGE)

    companion object {
        const val DEFAULT_PAGE = 20
        const val MAX_PAGE = 100
    }
}
