package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 회수(스펙 §6.3 `payment-refund-apply`) — 환불 줄이 `SUCCEEDED` 가 된 뒤 주문 줄 락 안에서 `applied_at` 이 비었을 때만 [PaymentOrderListener.onRefunded] 를 **한 번** 부른다.
 * 리스너가 던지면 이 트랜잭션이 되돌려지고(`applied_at` 은 빈 채) **돈의 상태(환불 줄 · 주문)는 그대로**이며 작업이 재시도한다.
 *
 * **지급과의 순서**(`onRefunded` 가 `onPaid` 보다 먼저 가지 않는다): 아직 지급되지 않은 주문이 일부만 환불됐으면 여기서 먼저 지급([PaymentFulfillment.fulfil])을 하고 회수한다.
 * 지급 전에 **전액** 환불됐으면 지급은 건너뛰고([PaymentFulfillment.fulfil] 이 `REFUNDED` 를 지급하지 않는다) `onRefunded` 만 불린다 — 앱의 회수는 "지급했으면 되돌린다"로 멱등하게 쓴다.
 */
class PaymentRefundApplier(
    private val store: PaymentLedgerStore,
    private val listener: PaymentOrderListener,
    private val fulfillment: PaymentFulfillment,
    private val writer: PaymentLedgerWriter,
    /** 구독 주문이 전액 환불되면 구독을 끝내는 고리(구독을 켠 조립에서만). */
    private val subscriptions: SubscriptionLifecycle? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이번에 리스너를 불렀으면 true. 리스너가 던지면 그대로 던진다. 할 일이 없으면(성공한 줄이 아님 · 이미 회수) false. */
    fun apply(refundId: String): Boolean {
        val orderId = store.findRefund(refundId)?.orderId ?: return false
        return store.inTransaction { tx ->
            val order = tx.lockOrder(orderId) ?: return@inTransaction false
            val refund = tx.lockRefund(refundId) ?: return@inTransaction false
            if (refund.status != PaymentRefundStatus.SUCCEEDED || refund.appliedAt != null) return@inTransaction false
            if (order.fulfilledAt == null) fulfillment.fulfil(orderId)
            val current = checkNotNull(tx.lockOrder(orderId))
            listener.onRefunded(current, refund)
            if (current.originType == PaymentOriginType.SUBSCRIPTION && current.status == PaymentOrderStatus.REFUNDED) subscriptions?.onOrderRefunded(tx, current, keepSubscription = refund.keepSubscription)
            val at = writer.now()
            tx.updateRefund(refund.copy(appliedAt = at, updatedAt = at))
            tx.appendEvent(writer.refundEvent(orderId, refundId, PaymentEventType.REFUND_APPLIED, PaymentActor.SYSTEM))
            // 이 주문에 미회수 SUCCEEDED 줄이 더 없으면 낡은 회수 표시는 그 자리에서 푼다. 돈 이유는 건드리지 않는다
            val latest = checkNotNull(tx.lockOrder(orderId))
            if (latest.reviewReason == PaymentReviewReason.REFUND_APPLY_UNRESOLVED && SupplyWork.unapplied(tx.listRefunds(orderId)).isEmpty()) {
                writer.touch(tx, latest, PaymentEventType.CONVERGED, PaymentActor.SYSTEM, null, "REFUND_APPLY_UNRESOLVED_CLEARED") { it.copy(reviewReason = null) }
            }
            true
        }
    }

    /** 던지지 않는다 — 요청 스레드가 확정 직후 한 번 시도한다. 실패하면 false 이고 회수 작업이 재시도한다. */
    fun tryApply(refundId: String): Boolean =
        try {
            apply(refundId)
        } catch (e: Exception) {
            log.warn("payment refund application failed refund={} exception={}; the apply task will retry", refundId, e.javaClass.simpleName)
            false
        }
}
