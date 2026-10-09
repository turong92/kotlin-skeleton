package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefund
import org.springframework.stereotype.Component

/**
 * 결제 장부의 두 훅(스펙 §4.2) — 응원 기록을 남기고 되돌린다. **두 훅은 주문 줄 락 안에서 돈다**: 그동안 그 주문의 웹훅 · 승인 · 환불이 기다리므로 여기서는 응원 기록 한 줄만 쓴다
 * (알림 · 메일 · 외부 호출을 넣지 않는다. 필요하면 큐로 — 예: `NoteService` 의 내보내기). 둘 다 **멱등**이다.
 */
@Component
class CheerOrderListener(private val cheers: CheerRepository) : PaymentOrderListener {
    /** 응원 기록 한 줄. 같은 주문이 두 번 와도 한 줄이다. 계정이 이미 지워진 주문(탈퇴 뒤 늦게 `PAID` 가 된 경우)은 기록을 남기지 않는다 — 지워진 계정의 줄을 되살리지 않는다. */
    override fun onPaid(order: PaymentOrder) {
        if (order.accountErasedAt != null) return
        cheers.insertPaid(
            Cheer(
                orderId = order.id,
                accountId = order.accountId,
                amount = order.amount.amount,
                currency = order.amount.normalizedCurrency,
                refundedAmount = 0,
                status = CheerStatus.PAID,
                createdAt = order.paidAt ?: order.createdAt,
                refundedAt = null,
            ),
        )
    }

    /**
     * 환불된 금액을 올린다. 일부 환불이면 `PAID` 로 남고, 전액이면 `REFUNDED` + 환불 시각. **지급한 적 없는 주문에도 불린다**(지급 전 전액 환불) — 기록이 없으면 아무것도 하지 않는다.
     */
    override fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {
        val full = order.status == PaymentOrderStatus.REFUNDED || order.refundedAmount >= order.amount.amount
        cheers.applyRefund(order.id, order.refundedAmount, full, refund.completedAt ?: order.updatedAt)
    }
}
