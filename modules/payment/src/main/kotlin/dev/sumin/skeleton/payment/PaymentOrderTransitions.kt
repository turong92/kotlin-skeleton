package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentOrderStatus.CANCELED
import dev.sumin.skeleton.payment.PaymentOrderStatus.CONFIRMING
import dev.sumin.skeleton.payment.PaymentOrderStatus.EXPIRED
import dev.sumin.skeleton.payment.PaymentOrderStatus.FAILED
import dev.sumin.skeleton.payment.PaymentOrderStatus.PAID
import dev.sumin.skeleton.payment.PaymentOrderStatus.PARTIALLY_REFUNDED
import dev.sumin.skeleton.payment.PaymentOrderStatus.PENDING
import dev.sumin.skeleton.payment.PaymentOrderStatus.REFUNDED

/**
 * 주문 상태 전이표(스펙 §5.2) — **상태를 바꾸는 코드는 모두 이것을 거친다.**
 * 표에 없는 전이는 버그이지 업무 상황이 아니므로 [IllegalStateException] 이다(사용자에게 나가지 않는다).
 *
 * 표에 **없는** 것이 중요하다: `PAID` 이후 → `FAILED` · `EXPIRED` · `CANCELED` 는 없다. 늦게 온 실패 신호가 받은 돈을 되돌리지 못한다.
 * `FAILED` · `EXPIRED` · `CANCELED` → `PAID` 는 있지만 **결제사 조회 증거**일 때만이다 — 그 증거를 보는 곳은 [PaymentConvergence] 뿐이다.
 */
object PaymentOrderTransitions {
    private val allowed: Map<PaymentOrderStatus, Set<PaymentOrderStatus>> = mapOf(
        PENDING to setOf(CONFIRMING, CANCELED, EXPIRED, PAID),
        CONFIRMING to setOf(PAID, FAILED, CONFIRMING),
        FAILED to setOf(PAID),
        EXPIRED to setOf(PAID),
        CANCELED to setOf(PAID),
        PAID to setOf(PARTIALLY_REFUNDED, REFUNDED),
        PARTIALLY_REFUNDED to setOf(PARTIALLY_REFUNDED, REFUNDED),
        REFUNDED to emptySet(),
    )

    fun isAllowed(from: PaymentOrderStatus, to: PaymentOrderStatus): Boolean = to in allowed.getValue(from)

    fun require(from: PaymentOrderStatus, to: PaymentOrderStatus) {
        check(isAllowed(from, to)) { "Order status transition $from -> $to is not allowed" }
    }
}
