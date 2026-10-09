package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderExpiryTest {
    private val ttl = Duration.ofMinutes(30)

    @Test
    fun `an abandoned order expires after the provider says there is no payment`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        val expired = kit.order(order.id)
        assertEquals(PaymentOrderStatus.EXPIRED, expired.status)
        assertEquals("EXPIRED_UNPAID", expired.failureCode)
        assertEquals(kit.time.now(), expired.closedAt)
        assertEquals(PaymentLookupQuery(providerPaymentId = null, orderId = order.id), kit.gateway.lookupCalls.single(), "asked once, by order number")
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state)
        assertEquals(0, kit.gateway.confirmCalls.size, "the server never confirms on its own")
    }

    @Test
    fun `when the provider itself expired the authentication the order expires too`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.expire("pay_1")
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
    }

    @Test
    fun `while the provider says in progress the expiry waits and retries and the order stays pending`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        val task = kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single()

        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertEquals(State.PENDING, task.state)
        assertEquals(1, task.attempts)
        assertNull(kit.order(order.id).reviewReason)

        kit.gateway.expire("pay_1")
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
    }

    @Test
    fun `a payment that was completed at the provider after the deadline pays the order instead of expiring it`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.settle("pay_1")
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals("pay_1", paid.providerPaymentId, "the payment id is taken from the evidence so a refund can find it")
        assertEquals("HYUNDAI", paid.cardIssuer)
        assertEquals(1, kit.tasks.pending(PaymentTaskTypes.ORDER_FULFIL).size)
    }

    @Test
    fun `the expiry task does not run before the deadline`() {
        val kit = PaymentLedgerKit()
        kit.create()
        kit.advance(ttl.minusSeconds(1))

        assertEquals(0, kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE)))
    }

    @Test
    fun `the expiry leaves paid, confirming and canceled orders alone without asking the provider`() {
        val kit = PaymentLedgerKit()
        val canceled = kit.create(key = "k1").order.also { kit.orders.abandon("acc_1", it.id) }
        val paid = kit.createAuthenticated(key = "k2", paymentKey = "pay_2").also { kit.orders.confirm("acc_1", it.id, "pay_2", it.amount) }
        val lookupsBefore = kit.gateway.lookupCalls.size // 승인이 카드 정보를 채우려고 한 번 물었다
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.CANCELED, kit.order(canceled.id).status)
        assertEquals(PaymentOrderStatus.PAID, kit.order(paid.id).status)
        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size, "the expiry did not ask the provider")
        assertEquals(2, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).count { it.state == State.DONE })
    }

    @Test
    fun `an order created under another mode is closed without asking the provider and without a flag - it never claimed an approval`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertNull(kit.order(order.id).reviewReason)
        assertEquals(0, kit.tasks.dead(PaymentTaskTypes.ORDER_EXPIRE).size)
    }

    @Test
    fun `a mode that changed is judged before the lookup - a lookup that would fail does not loop the job`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.gateway.mode = PaymentMode.LIVE
        kit.gateway.failLookups(5)
        kit.advance(ttl)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_EXPIRE))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state)
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().attempts)
        assertEquals(0, kit.gateway.lookupCalls.size)
    }

    @Test
    fun `a provider bean that is missing makes the expiry retry instead of dying before its deadline`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        val handler = OrderExpireTaskHandler(kit.store, PaymentProviderRouter(listOf(ScriptedGateway(providerId = "elsewhere")), kit.properties), kit.convergence)

        kotlin.test.assertFailsWith<PaymentTaskRetryException> { handler.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    private fun withoutProvider(kit: PaymentLedgerKit) =
        OrderExpireTaskHandler(kit.store, PaymentProviderRouter(listOf(ScriptedGateway(providerId = "elsewhere")), kit.properties), kit.convergence)

    @Test
    fun `a pending order whose provider bean is missing closes without a mark once its deadline has passed - it never claimed an approval`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.advance(ttl)

        withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12))

        val expired = kit.order(order.id)
        assertEquals(PaymentOrderStatus.EXPIRED, expired.status)
        assertEquals("EXPIRED_UNPAID", expired.failureCode)
        assertNull(expired.reviewReason)
        assertTrue(kit.review.flagged.isEmpty())
    }

    @Test
    fun `a pending order whose provider bean is missing is retried before its deadline`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.advance(ttl.minusSeconds(1))

        kotlin.test.assertFailsWith<PaymentTaskRetryException> { withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) }

        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    @Test
    fun `a pending order with a received mark is not closed without its provider either - the job dies for a person`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(order.id)!!, PaymentReviewReason.AMOUNT_MISMATCH, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)) }
        kit.advance(ttl)

        kotlin.test.assertFailsWith<PaymentTaskPermanentException> { withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) }

        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
    }

    @Test
    fun `an expiry task for a confirming order leaves it alone even without its provider - only the reconcile task handles it`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ScriptedGateway.ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        kit.advance(ttl)

        withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, created.id, 1, 12))

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
        assertNull(kit.order(created.id).reviewReason)
    }
}
