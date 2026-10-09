package dev.sumin.skeleton.payment

/**
 * 끝나지 않은 지급 · 회수를 다시 돌리는 작업을 넣는 한 곳(스펙 §15). 사람의 「결제사와 맞추기」([PaymentRefunds.sync])와, **돈 이유가 사람 없이 풀리는 자리**가 같이 쓴다.
 *
 * 돈 이유(`REFUND_MISMATCH` 등)가 칸을 차지한 동안 죽은 지급 · 회수 작업은 표시도 이력도 남기지 못한다(지급 쪽 이유는 돈 이유 밑으로 못 들어간다). 그 칸이 비면 주문은
 * 「확인 필요」 목록에서 사라지므로, 풀리는 같은 트랜잭션에서 작업을 다시 넣는다 — 다시 죽으면 그때는 칸이 비어 있어 표시된다. 작업은 멱등이라 겹쳐 들어가도 한 번만 일한다.
 * 부르는 쪽이 주문 줄을 잠근 트랜잭션 안이어야 한다.
 */
internal class SupplyWork(private val tasks: PaymentTasks) {
    /** 미지급 `PAID` 계열이면 지급 작업. 지급 전에 전액 환불된 주문(`REFUNDED`)은 지급하지 않으므로 넣지 않는다. */
    fun requeueFulfil(order: PaymentOrder) {
        if (order.fulfilledAt == null && (order.status == PaymentOrderStatus.PAID || order.status == PaymentOrderStatus.PARTIALLY_REFUNDED)) {
            tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        }
    }

    /** [refundIds] 중 아직 `SUCCEEDED` 이고 회수되지 않은 줄마다 회수 작업. */
    fun requeueApply(tx: PaymentLedgerTx, refundIds: List<String>) {
        refundIds.filter { id -> tx.lockRefund(id)?.let { it.status == PaymentRefundStatus.SUCCEEDED && it.appliedAt == null } == true }
            .forEach { tasks.enqueue(PaymentTask(PaymentTaskTypes.REFUND_APPLY, it)) }
    }

    companion object {
        /** 성공했는데 아직 회수되지 않은 줄. */
        fun unapplied(refunds: List<PaymentRefund>): List<String> =
            refunds.filter { it.status == PaymentRefundStatus.SUCCEEDED && it.appliedAt == null }.map { it.id }
    }
}
