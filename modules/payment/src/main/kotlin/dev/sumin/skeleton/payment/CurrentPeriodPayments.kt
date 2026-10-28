package dev.sumin.skeleton.payment

import java.time.Instant

/**
 * 이번 기간에 돈을 낸 주문과 그 **순액**(결제사 기준 총액 - 이 해지가 아닌 환불). [covers] 가 없으면 기간 중에 낸 플랜 변경 차액은 낸 날부터, 나머지는 기간 시작부터 기간 끝까지를 덮는다.
 * [covers] 가 있는 줄은 주기를 늘린 갈아타기가 **이전 기간의 주문에서 옮겨 온 공제분**이다(그 주문의 몫이 새 기간의 값으로 넘어왔다) - 환불은 그 원래 주문으로 나간다.
 */
class PeriodPayment(val order: PaymentOrder, val net: Long, private val covers: Instant? = null) {
    fun coversFrom(periodStart: Instant): Instant = covers ?: if (order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE) order.paidAt ?: periodStart else periodStart
}

/**
 * 「이번 기간에 낸 돈」 - 중도 해지의 환불 계산과 주기를 늘리는 갈아타기의 남은 몫 공제가 같은 입력을 쓴다.
 * 이 해지가 이미 돌려준 몫은 빼지 않는다 - 다시 계산해도 같은 값이 나오게.
 *
 * **주기를 늘려 이번 기간을 연 변경 주문**(새 가격 = 결제액 + 공제액: `original_amount` · `discount_amount`)이 있으면, 새 가격 전체가 이번 기간의 돈이다:
 * 공제액은 직전 기간에 낸 주문들(새것부터)에서 그만큼 옮겨 와 새 기간 시작부터 덮는 줄로 더한다. 그래서 중도 해지 환불이 변경 결제분만이 아니라 원래 월 주문의 부분 환불로도 나간다.
 */
class CurrentPeriodPayments(private val store: PaymentLedgerStore) {
    fun of(subscription: PaymentSubscription): List<PeriodPayment> {
        val start = subscription.currentPeriodStart ?: return emptyList()
        val end = subscription.currentPeriodEnd ?: return emptyList()
        val paid = store.listSubscriptionOrders(subscription.id, ORDERS_LOOKED_AT).filter { it.status.isPaidFamily && it.paidAt != null }
        val inPeriod = paid.filter { !it.paidAt!!.isBefore(start) && it.paidAt!!.isBefore(end) }.map { PeriodPayment(it, netOf(it)) }
        val opener = inPeriod.map { it.order }.firstOrNull { opensPeriod(subscription, it) }
        val carried = if (opener == null) emptyList() else carriedCredit(paid.filter { it.paidAt!!.isBefore(start) }, opener.discountAmount, start)
        return (inPeriod + carried).filter { it.net > 0 }
    }

    /** 직전 기간의 주문들에서 [credit] 만큼을 새것부터 옮긴다 - 각 주문이 아직 가진 순액을 넘기지 않는다. */
    private fun carriedCredit(earlier: List<PaymentOrder>, credit: Long, newStart: Instant): List<PeriodPayment> {
        var left = credit
        return earlier.sortedByDescending { it.paidAt }.mapNotNull { order ->
            val take = minOf(left, netOf(order))
            if (take <= 0) return@mapNotNull null
            left -= take
            PeriodPayment(order, take, covers = newStart)
        }
    }

    private fun netOf(order: PaymentOrder): Long {
        val ours = store.listRefunds(order.id).filter { it.reasonCode == PaymentRefundReason.SUBSCRIPTION_CANCEL.name && it.status == PaymentRefundStatus.SUCCEEDED }.sumOf { it.amount.amount }
        return RefundLines.baseOf(order) - (order.refundedAmount - ours)
    }

    companion object {
        private const val ORDERS_LOOKED_AT = 20

        /**
         * 이 변경 주문이 **지금 기간을 열었나** - 결제 시각이 기간 시작이다(새 주기를 시작하는 변경만 기준 시각을 결제 시각으로 옮긴다: 주기를 늘리는 변경 · 전액 결제하고 새 주기).
         * 공제 없는 변경(첫 달 무료 쿠폰 기간 등)도 기간을 산 주문이다. 차액만 내는 같은 주기 변경은 기간 중에 내므로 해당하지 않는다.
         */
        fun opensPeriod(subscription: PaymentSubscription, order: PaymentOrder): Boolean =
            order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE && order.paidAt != null && order.paidAt == subscription.currentPeriodStart
    }
}
