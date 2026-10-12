package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 결제사가 끝내 답하지 않아 막힌 첫 결제 주문을 운영자가 닫는다 — 돈 받은 주문을 닫으면 안 된다. */
class SubscriptionStuckChargeCloseTest {
    private val kit = PaymentLedgerKit()
    private val op = "op_1"

    private fun stuckFirstCharge(script: ConfirmScript): Pair<PaymentSubscription, PaymentOrder> {
        kit.gateway.script(script)
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        val pending = kit.subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
        assertEquals(SubscriptionStatus.PENDING, pending.status)
        return pending to kit.store.listSubscriptionOrders(id, 1).single()
    }

    @Test
    fun `when the provider still does not know the charge the order is closed as not paid, the seat is freed and the account can subscribe again, with the operator on record`() {
        val (sub, order) = stuckFirstCharge(ConfirmScript.ServerError) // 결제사가 5xx 로 답했고 그 주문을 모른다
        assertEquals(PaymentOrderStatus.CONFIRMING, order.status)

        val closed = kit.refunds.closeStuckSubscriptionCharge(op, order.id)

        assertEquals(PaymentOrderStatus.FAILED, closed.status)
        val ended = checkNotNull(kit.store.findSubscription(sub.id))
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertNull(ended.activeKey)
        val event = kit.store.listEvents(order.id).last()
        assertEquals(PaymentActor.OPERATOR, event.actor)
        assertEquals(op, event.actorId)
        assertEquals("OPERATOR_CLOSED", event.providerCode)
        // 자리가 풀렸으니 다시 구독할 수 있다
        assertNotNull(kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k2")).subscription)
    }

    @Test
    fun `an order the provider reports as paid is not closed - it is paid normally and the subscription starts`() {
        val (sub, order) = stuckFirstCharge(ConfirmScript.TimeoutApplied())

        val result = kit.refunds.closeStuckSubscriptionCharge(op, order.id)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(PaymentOrderStatus.PAID, result.status)
        assertEquals(SubscriptionStatus.ACTIVE, checkNotNull(kit.store.findSubscription(sub.id)).status)
    }

    @Test
    fun `only an unanswered subscription charge can be closed, and not while the provider is still working on it`() {
        val (_, order) = stuckFirstCharge(ConfirmScript.TimeoutNotApplied) // 결제사에 진행 중인 결제가 있다
        assertFailsWith<PaymentOrderNotClosableException> { kit.refunds.closeStuckSubscriptionCharge(op, order.id) }
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        kit.gateway.abort(kit.gateway.providerPaymentsOfOrder(order.id).single().paymentKey) // 결제사가 실패로 닫았다 — 이제 정상으로 닫힌다
        kit.refunds.closeStuckSubscriptionCharge(op, order.id)
        assertFailsWith<PaymentOrderNotClosableException> { kit.refunds.closeStuckSubscriptionCharge(op, order.id) }

        val cheer = kit.paid()
        assertFailsWith<PaymentOrderNotClosableException> { kit.refunds.closeStuckSubscriptionCharge(op, cheer.id) }
    }
}
