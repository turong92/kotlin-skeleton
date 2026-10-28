package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.payment.testing.FakeCouponSource
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 쿠폰 한 장의 한살이 - 예약 → 확정 / 해제 / 복원, 그리고 늦은 PAID. 돈 사고가 나는 자리라 하나씩 고정한다. */
class OrderDiscountLifecycleTest {
    private val coupons = FakeCouponSource().apply { add("c30", DiscountStage(DiscountStageType.PERCENT, 30)) }
    private val kit = PaymentLedgerKit(discountSources = listOf(coupons))
    private val claims = listOf(DiscountClaim("coupon", "c30"))
    private val op = "op_1"

    private fun discounted(key: String = "k1", account: String = "acc_1"): PaymentOrder =
        kit.create(accountId = account, key = key, claims = claims).order

    private fun pay(order: PaymentOrder, paymentKey: String = "pay_1"): PaymentOrder {
        kit.gateway.authenticate(paymentKey, order.id, order.amount)
        return kit.orders.confirm(order.accountId, order.id, paymentKey, order.amount)
    }

    private fun lineStatus(order: PaymentOrder) = kit.store.listOrderDiscounts(listOf(order.id)).single().status

    @Test
    fun `paid - the coupon is confirmed once, before the app listener, and the provider was charged the discounted amount`() {
        val order = discounted()
        val paid = pay(order)

        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(2100, kit.gateway.confirmRequests.single().amount.amount)
        assertEquals("USED", coupons.stateOf("c30"))
        assertEquals(OrderDiscountStatus.CONFIRMED, lineStatus(order))
        assertEquals(1, coupons.calls.count { it.startsWith("confirm") })
        assertEquals(1, kit.listener.paid.size)
        assertEquals(2100, kit.listener.paid.single().amount.amount)
    }

    @Test
    fun `a listener that throws rolls the coupon line back and the retry confirms it once`() {
        val order = discounted()
        kit.listener.failPaid(1)
        pay(order)   // 요청 스레드의 지급 시도가 던져서 지급 전에 멈춘다

        assertNull(kit.order(order.id).fulfilledAt)
        assertEquals(OrderDiscountStatus.RESERVED, lineStatus(order), "the confirm is undone with the failed fulfilment")

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(1, kit.listener.paid.size)
        assertEquals(OrderDiscountStatus.CONFIRMED, lineStatus(order))
        assertEquals("USED", coupons.stateOf("c30"))
    }

    @Test
    fun `expiry releases the coupon, the abandoned and declined orders too`() {
        val expired = discounted("k1")
        kit.advance(Duration.ofMinutes(30))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(expired.id).status)
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
        assertEquals(OrderDiscountStatus.RELEASED, lineStatus(expired))

        val abandoned = discounted("k2")
        assertEquals("RESERVED", coupons.stateOf("c30"))
        kit.orders.abandon("acc_1", abandoned.id)
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
        assertEquals(OrderDiscountStatus.RELEASED, lineStatus(abandoned))
    }

    @Test
    fun `a declined confirm releases the coupon`() {
        val order = discounted()
        kit.gateway.authenticate("pay_1", order.id, order.amount)
        kit.gateway.script(dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript.Decline("CARD_DECLINED"))
        runCatching { kit.orders.confirm("acc_1", order.id, "pay_1", order.amount) }

        assertEquals(PaymentOrderStatus.FAILED, kit.order(order.id).status)
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
        assertEquals(OrderDiscountStatus.RELEASED, lineStatus(order))
    }

    @Test
    fun `late PAID after expiry - the coupon is taken back when it is still free`() {
        val order = discounted()
        kit.advance(Duration.ofMinutes(30))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        kit.gateway.authenticate("pay_late", order.id, order.amount)
        kit.gateway.settle("pay_late")
        kit.refunds.sync(op, order.id)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals("USED", coupons.stateOf("c30"))
        assertEquals(order.id, coupons.holderOf("c30"))
        assertEquals(OrderDiscountStatus.CONFIRMED, lineStatus(order))
        assertNull(kit.order(order.id).reviewReason)
    }

    @Test
    fun `late PAID after another order took the coupon - both stay as they are, the late one is flagged DISCOUNT_CONFLICT and still delivers once`() {
        val first = discounted("k1")
        kit.advance(Duration.ofMinutes(30))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        val second = discounted("k2", "acc_2")   // 풀린 쿠폰을 다른 주문이 쥔다
        kit.gateway.authenticate("pay_late", first.id, first.amount)
        kit.gateway.settle("pay_late")
        kit.refunds.sync(op, first.id)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(PaymentOrderStatus.PAID, kit.order(first.id).status)
        assertEquals(PaymentReviewReason.DISCOUNT_CONFLICT, kit.order(first.id).reviewReason)
        assertEquals(1, kit.listener.paid.count { it.id == first.id }, "the buyer paid the discounted price, the delivery goes out")
        assertEquals(OrderDiscountStatus.RELEASED, lineStatus(first))
        assertEquals(second.id, coupons.holderOf("c30"), "the other order keeps the coupon")
        assertEquals("RESERVED", coupons.stateOf("c30"))
        assertEquals(PaymentOrderStatus.PENDING, kit.order(second.id).status)
    }

    @Test
    fun `a full refund gives the coupon back, a partial refund does not`() {
        val order = pay(discounted())
        kit.refunds.refund(op, order.id, "OPERATOR", 500, IdempotencyKey("r1"))
        assertEquals("USED", coupons.stateOf("c30"), "partial: no restore")
        assertEquals(OrderDiscountStatus.CONFIRMED, lineStatus(order))
        assertEquals(0, coupons.calls.count { it.startsWith("restore") })

        kit.refunds.refund(op, order.id, "OPERATOR", null, IdempotencyKey("r2"))

        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
        assertEquals("AVAILABLE", coupons.stateOf("c30"))
        assertEquals(OrderDiscountStatus.RESTORED, lineStatus(order))
        assertEquals(1, coupons.calls.count { it.startsWith("restore") })
    }

    @Test
    fun `a full refund before fulfilment frees the coupon too - it was held and never used`() {
        val order = discounted()
        kit.listener.failPaid(10)   // 지급이 안 끝난 채로 둔다
        pay(order)
        assertEquals(OrderDiscountStatus.RESERVED, lineStatus(order))

        kit.refunds.refund(op, order.id, "OPERATOR", null, IdempotencyKey("r1"))

        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
        // 메모리 대역은 DB 트랜잭션에 묶이지 않아 앞선 확정 시도의 흔적이 남는다 - 장부가 풀기를 요청했는지(release, restore 아님)와 줄의 상태를 본다
        assertEquals(1, coupons.calls.count { it == "release:${order.id}" })
        assertEquals(0, coupons.calls.count { it.startsWith("restore") })
        assertEquals(OrderDiscountStatus.RELEASED, lineStatus(order))
    }

    @Test
    fun `without any discount source nothing is asked of the discount side`() {
        val plain = PaymentLedgerKit()
        val order = plain.paid()
        plain.refunds.refund(op, order.id, "OPERATOR", null, IdempotencyKey("r1"))

        assertEquals(emptyList(), plain.store.listOrderDiscounts(listOf(order.id)))
        assertEquals(PaymentOrderStatus.REFUNDED, plain.order(order.id).status)
        assertNull(plain.order(order.id).originalAmount)
        assertEquals(0, plain.order(order.id).discountAmount)
    }
}
