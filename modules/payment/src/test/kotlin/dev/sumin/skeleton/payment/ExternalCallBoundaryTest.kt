package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 스펙 §6.2 의 "외부 호출은 락 · 트랜잭션 밖" 을 고정한다. 메모리 저장소는 같은 스레드의 열린 트랜잭션에 합류하므로 **시험 대역이 먼저 지켜 본다**:
 * [dev.sumin.skeleton.payment.testing.ScriptedGateway] 는 열린 장부 트랜잭션 안에서 불리면 던진다.
 * 서비스 쪽은 바깥 트랜잭션 안에서 불리면 결제사를 부르기 전에 던진다(포트 계약 [PaymentLedgerStore.hasOpenTransaction]).
 */
class ExternalCallBoundaryTest {
    @Test
    fun `the scripted provider refuses a call made inside an open ledger transaction`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()

        assertFailsWith<AssertionError> { kit.store.inTransaction { kit.gateway.confirm(PaymentConfirmRequest("pay_1", order.amount, merchantReferenceId = order.id)) } }

        assertEquals(1, kit.gateway.callsInsideTransaction.size)
        assertEquals(0, kit.gateway.confirmCalls.size, "a refused call is not recorded as a provider call")
    }

    @Test
    fun `a whole life of an order - create, confirm that times out, reconcile, expire - never calls the provider inside a transaction`() {
        val kit = PaymentLedgerKit()
        val unpaid = kit.create(key = "unpaid").order
        val order = kit.createAuthenticated(key = "paid", paymentKey = "pay_1")
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)
        kit.create(key = "paid") // 같은 키로 다시 — 결제창 값을 다시 만든다
        kit.advance(java.time.Duration.ofMinutes(31))

        kit.runTasks()

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(unpaid.id).status)
        assertTrue(kit.gateway.confirmCalls.isNotEmpty() && kit.gateway.lookupCalls.isNotEmpty() && kit.gateway.checkoutCalls.isNotEmpty())
        assertEquals(emptyList(), kit.gateway.callsInsideTransaction)
    }

    @Test
    fun `confirm, reconcile and expiry refuse an outer transaction before they claim anything or call the provider`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        assertFailsWith<IllegalStateException> { kit.store.inTransaction { kit.orders.confirm("acc_1", order.id, "pay_1", order.amount) } }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertNull(kit.order(order.id).providerPaymentId)
        assertEquals(0, kit.gateway.confirmCalls.size)

        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)
        val lookupsBefore = kit.gateway.lookupCalls.size
        assertFailsWith<IllegalStateException> { kit.store.inTransaction { kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12)) } }
        assertFailsWith<IllegalStateException> { kit.store.inTransaction { kit.expire.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) } }

        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size)
    }
}
