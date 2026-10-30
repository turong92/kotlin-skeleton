package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.campaign.Referrals
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentSettlement
import org.springframework.stereotype.Component

/**
 * 결제 장부의 두 훅(스펙 §4.2) — 응원 기록을 남기고 되돌린다. 결제 장부는 `PaymentOrderListener` 빈을 **하나**만 받으므로 이 클래스는 훅 자체가 아니라 [dev.sumin.skeleton.app.sample.orders.SampleOrderListener] 가 부르는 한쪽이다(응원 · 리뷰 구매 신호). **두 훅은 주문 줄 락 안에서 돈다**: 그동안 그 주문의 웹훅 · 승인 · 환불이 기다리므로 여기서는 응원 기록 한 줄만 쓴다
 * (알림 · 메일 · 외부 호출을 넣지 않는다. 필요하면 큐로 — 예: `NoteService` 의 내보내기). 둘 다 **멱등**이다.
 */
@Component
class CheerOrderListener(private val cheers: CheerRepository, private val referrals: Referrals) {
    /** 응원 기록 한 줄. 같은 주문이 두 번 와도 한 줄이다. 계정이 이미 지워진 주문(탈퇴 뒤 늦게 `PAID` 가 된 경우)은 기록을 남기지 않는다 — 지워진 계정의 줄을 되살리지 않는다. */
    fun onPaid(order: PaymentOrder) {
        if (order.itemType != CheerCatalog.ITEM_TYPE) return // 구독 같은 다른 상품의 주문에는 응원 기록을 남기지 않는다
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
        // 첫 응원 결제가 친구초대의 초대자 보상(`qualifyOn: SIGNAL`, 신호 이름 `first-payment`)을 연다 - 몇 번 불러도 한 번만 먹는다. 돈이 오간 주문만: 쿠폰으로 0원이 된 주문 · 결제 면제 주문은 「결제」가 아니다.
        // (같은 트랜잭션 안에서 지급 건 줄과 작업만 넣는다 - 외부 호출이 아니다)
        if (order.settlement == PaymentSettlement.PROVIDER && referrals.qualify(order.accountId, FIRST_PAYMENT)) cheers.markOpenedReferral(order.id)
    }

    /**
     * 환불된 금액을 올린다. 일부 환불이면 `PAID` 로 남고, 전액이면 `REFUNDED` + 환불 시각. **지급한 적 없는 주문에도 불린다**(지급 전 전액 환불) — 기록이 없으면 아무것도 하지 않는다.
     */
    fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {
        if (order.itemType != CheerCatalog.ITEM_TYPE) return
        val full = order.status == PaymentOrderStatus.REFUNDED || order.refundedAmount >= order.amount.amount
        cheers.applyRefund(order.id, order.refundedAmount, full, refund.completedAt ?: order.updatedAt)
        // 초대자 보상이 보류(HELD) · 운영자 확인(REVIEW) 중이면, 자격을 만든 그 첫 결제가 **전액** 환불될 때만 거둔다 - 다른 주문의 환불 · 일부 환불은 건드리지 않는다(보류 기간이 회수를 대신한다)
        if (full && cheers.openedReferral(order.id)) referrals.cancelPending(order.accountId, "refunded")
    }

    private companion object {
        const val FIRST_PAYMENT = "first-payment"
    }
}
