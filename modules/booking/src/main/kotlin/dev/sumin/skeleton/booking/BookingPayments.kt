package dev.sumin.skeleton.booking

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PurchaseQuote

/**
 * 예약 ↔ 결제 이음새. `payment` 의 `PaymentCatalog` · `PaymentOrderListener` 는 앱 빈 하나뿐이라 앱이 `itemType = "booking"` 으로 갈라 이 클래스를 부른다(`catalog` 선례 그대로):
 *
 * ```kotlin
 * PaymentCatalog { request -> if (request.itemType == BookingPayments.ITEM_TYPE) bookingPayments.itemFor(request.itemRef, request.accountId) else … }
 * // PaymentOrderListener.onPaid / onRefunded 도 같다 - order.itemType == BookingPayments.ITEM_TYPE 이면 bookingPayments.onPaid(order) / onRefunded(order, refund)
 * ```
 *
 * 흐름: 결제창을 열 때 [itemFor] 가 결제 시작([BookingHolds.startCheckout], `POST /holds/{id}/checkout`)이 금액을 박고 자리를 고정한다 → 주문(`itemFor`)은 시작한 보류만 만든다 → 결제되면 [onPaid] 가 확정 · 늦은 결제의 다시 잡기 · 못 잡으면 자동 환불을 맡는다
 * → 전액 환불되면 [onRefunded] 가 확정을 취소하고 자리를 돌려준다(부분 환불은 자리 그대로). `payment` 가 클래스패스에 있을 때만 빈으로 선다.
 *
 * `onPaid` · `onRefunded` 는 결제의 주문 줄 락 안에서 돈다 - 여기서는 DB 쓰기와 환불 작업 넣기만 하고 결제사를 부르지 않는다(환불은 `booking-refund` 작업이 한다).
 */
class BookingPayments(private val holds: BookingHolds, private val pools: BookingPools) {
    /** 결제를 시작한 보류의 박힌 금액 · 이름(**부수 효과 없음** - 견적 호출도 여기를 타므로) - 시작하지 않았으면 `409 CHECKOUT_REQUIRED`, 보류가 내 것이 아니거나 없으면 같은 404, 무료 · 확정된 보류는 `409 BOOKING.NOT_PAYABLE` */
    fun itemFor(holdId: String, accountId: String): PurchaseQuote {
        val hold = holds.priceHold(accountId, holdId)
        val pool = pools.get(hold.poolId)
        return PurchaseQuote(PaymentAmount(checkNotNull(hold.amount), checkNotNull(pool.currency)), pool.name.take(MAX_ORDER_NAME))
    }

    /**
     * 주문이 결제됐다. 주문 하나에 한 번만 효과가 있다(다시 불려도 같다). 쿠폰으로 깎였어도 확정은 **깎이기 전 금액**(자리 가격의 합)으로 맞춘다.
     * 결과가 [OrderOutcome.UNFULFILLED] · [OrderOutcome.DUPLICATE] 면 환불 작업이 이미 걸렸다.
     */
    fun onPaid(order: PaymentOrder): PaidOutcome =
        holds.settlePaid(order.id, order.accountId, order.itemRef, order.originalAmount ?: order.amount.amount, order.amount.currency)

    /** 전액 환불된 주문의 확정을 취소하고 자리를 돌려준다. 부분 환불 · 이 주문으로 확정된 보류가 아니면 아무 일도 없다 */
    fun onRefunded(order: PaymentOrder, @Suppress("UNUSED_PARAMETER") refund: PaymentRefund) {
        if (order.status == PaymentOrderStatus.REFUNDED) holds.cancelRefunded(order.id)
    }

    companion object {
        const val ITEM_TYPE = "booking"

        /** 결제창에 보이는 이름의 상한 (`PurchaseQuote.orderName`) */
        private const val MAX_ORDER_NAME = 100
    }
}
