package dev.sumin.skeleton.payment

import java.time.Instant

/** 이번 기간에 돈을 낸 주문과 그 **순액**(결제사 기준 총액 - 이 해지가 아닌 환불). [coversFrom] 부터 기간 끝까지가 이 돈이 덮는 구간이다. */
class PeriodPayment(val order: PaymentOrder, val net: Long) {
    /** 기간 중에 낸 플랜 변경 차액은 낸 날부터, 나머지는 기간 시작부터 덮는다 */
    fun coversFrom(periodStart: Instant): Instant = if (order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE) order.paidAt ?: periodStart else periodStart
}

/**
 * 「이번 기간에 낸 돈」 - 중도 해지의 환불 계산과 주기를 늘리는 갈아타기의 남은 몫 공제가 같은 입력을 쓴다.
 * 이 해지가 이미 돌려준 몫은 빼지 않는다 - 다시 계산해도 같은 값이 나오게.
 */
class CurrentPeriodPayments(private val store: PaymentLedgerStore) {
    fun of(subscription: PaymentSubscription): List<PeriodPayment> {
        val start = subscription.currentPeriodStart ?: return emptyList()
        val end = subscription.currentPeriodEnd ?: return emptyList()
        return store.listSubscriptionOrders(subscription.id, ORDERS_LOOKED_AT)
            .filter { it.status.isPaidFamily && it.paidAt != null && !it.paidAt.isBefore(start) && it.paidAt.isBefore(end) }
            .map { order ->
                val ours = store.listRefunds(order.id).filter { it.reasonCode == PaymentRefundReason.SUBSCRIPTION_CANCEL.name && it.status == PaymentRefundStatus.SUCCEEDED }.sumOf { it.amount.amount }
                PeriodPayment(order, RefundLines.baseOf(order) - (order.refundedAmount - ours))
            }
            .filter { it.net > 0 }
    }

    private companion object {
        const val ORDERS_LOOKED_AT = 20
    }
}
