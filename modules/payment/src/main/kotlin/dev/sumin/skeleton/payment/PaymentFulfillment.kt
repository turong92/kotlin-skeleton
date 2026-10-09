package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 지급(스펙 §6.2 ④) — 「결제됨」 커밋과 **떼어 놓은** 걸음. 주문 줄 락 안에서 `fulfilled_at` 이 비었을 때만 [PaymentOrderListener.onPaid] 를 부르므로
 * 같은 DB 를 쓰는 지급은 정확히 한 번이다. **지급 전에 전액 환불된 주문(`REFUNDED`)은 지급하지 않는다.** 리스너가 던지면 이 트랜잭션이 되돌려지고(`fulfilled_at` 은 빈 채) `PAID` 는 그대로다.
 */
class PaymentFulfillment(
    private val store: PaymentLedgerStore,
    private val listener: PaymentOrderListener,
    private val writer: PaymentLedgerWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 이번에 리스너를 불렀으면 true. 리스너가 던지면 그대로 던진다. 지급할 것이 없으면(미결제 · 이미 지급) false. */
    fun fulfil(orderId: String): Boolean =
        store.inTransaction { tx ->
            val order = tx.lockOrder(orderId) ?: return@inTransaction false
            if (!order.status.isPaidFamily || order.fulfilledAt != null) return@inTransaction false
            // 지급 전에 전액 환불된 주문은 지급하지 않는다 — 돌려준 돈의 물건을 내주지 않는다. `fulfilled_at` 은 비어 있는 채(물건이 나간 적 없다)이고,
            // 앱은 그래도 `onRefunded` 를 받는다(지급한 적이 없으면 되돌릴 것이 없다 — 앱의 회수는 "있으면 되돌린다"로 쓴다)
            if (order.status == PaymentOrderStatus.REFUNDED) return@inTransaction false
            listener.onPaid(order)
            val fulfilled = writer.touch(tx, order, PaymentEventType.FULFILLED, PaymentActor.SYSTEM) { it.copy(fulfilledAt = writer.now()) }
            // 다른 길로(작업이 죽은 뒤 요청 스레드 · 사람의 맞추기가 다시 넣은 작업) 지급이 성공하면 낡은 지급 표시는 그 자리에서 푼다. 돈 이유는 건드리지 않는다
            if (fulfilled.reviewReason == PaymentReviewReason.FULFILMENT_UNRESOLVED) {
                writer.touch(tx, fulfilled, PaymentEventType.CONVERGED, PaymentActor.SYSTEM, null, "FULFILMENT_UNRESOLVED_CLEARED") { it.copy(reviewReason = null) }
            }
            true
        }

    /** 던지지 않는다 — 요청 스레드가 확정 직후 한 번 시도한다. 실패하면 false 이고 지급 작업이 재시도한다. */
    fun tryFulfil(orderId: String): Boolean =
        try {
            fulfil(orderId)
        } catch (e: Exception) {
            log.warn("payment fulfilment failed order={} exception={}; the fulfil task will retry", orderId, e.javaClass.simpleName)
            false
        }
}
