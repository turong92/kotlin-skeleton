package dev.sumin.skeleton.app.sample.orders

import dev.sumin.skeleton.app.sample.cheer.CheerOrderListener
import dev.sumin.skeleton.app.sample.reviews.ReviewPurchaseSignals
import dev.sumin.skeleton.booking.BookingPayments
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentRefund
import org.springframework.stereotype.Component

/**
 * 이 앱의 결제 장부 훅 하나 — 결제 장부는 [PaymentOrderListener] 빈을 하나만 받으므로 앱이 한 곳에서 둘을 부른다: 응원 기록([CheerOrderListener]) · 리뷰 구매 신호([ReviewPurchaseSignals]) · 예약 확정([BookingPayments]). **두 훅은 주문 줄 락 안에서 돈다** — 각 쪽은 자기 표에 줄 하나만 쓰고 외부 호출을 하지 않는다. 둘 다 멱등이다.
 */
@Component
class SampleOrderListener(
    private val cheer: CheerOrderListener,
    private val reviews: ReviewPurchaseSignals,
    private val booking: BookingPayments,
) : PaymentOrderListener {
    override fun onPaid(order: PaymentOrder) {
        // 예약 주문(`itemType = "booking"`)은 예약이 자리를 확정한다 - 응원 · 리뷰 신호는 자기 상품 종류만 본다
        if (order.itemType == BookingPayments.ITEM_TYPE) booking.onPaid(order)
        cheer.onPaid(order)
        reviews.onPaid(order)
    }

    override fun onRefunded(order: PaymentOrder, refund: PaymentRefund) {
        if (order.itemType == BookingPayments.ITEM_TYPE) booking.onRefunded(order, refund)
        cheer.onRefunded(order, refund)
        reviews.onRefunded(order)
    }
}
