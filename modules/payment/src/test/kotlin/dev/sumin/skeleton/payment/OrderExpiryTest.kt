package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderExpiryTest {
    private val ttl = Duration.ofMinutes(30)
    private val expire = setOf(PaymentTaskTypes.ORDER_EXPIRE)

    @Test
    fun `an abandoned order expires after the provider says there is no payment`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.advance(ttl)

        kit.runTasks(expire)

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
        assertEquals(PaymentLookupQuery(providerPaymentId = null, orderId = order.id), kit.gateway.lookupCalls.single(), "asked once, by order number")
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state)
        assertEquals(0, kit.gateway.confirmCalls.size, "the server never confirms on its own")
    }

    @Test
    fun `while the provider says in progress the expiry waits and the order stays pending`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.advance(ttl)

        kit.runTasks(expire)

        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertEquals(State.PENDING, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).single().state)

        kit.gateway.expire("pay_1")
        kit.runTasks(expire)
        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(order.id).status)
    }

    @Test
    fun `a payment that was completed at the provider after the deadline pays the order instead of expiring it`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.settle("pay_1")
        kit.advance(ttl)

        kit.runTasks(expire)

        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals("pay_1", paid.providerPaymentId, "the payment id is taken from the evidence so a refund can find it")
        assertEquals(1, kit.tasks.pending(PaymentTaskTypes.ORDER_FULFIL).size)
    }

    @Test
    fun `the expiry leaves paid and canceled orders alone without asking the provider`() {
        val kit = PaymentLedgerKit()
        val canceled = kit.create(key = "k1").order.also { kit.orders.abandon("acc_1", it.id) }
        val paid = kit.createAuthenticated(key = "k2", paymentKey = "pay_2").also { kit.orders.confirm("acc_1", it.id, "pay_2", it.amount) }
        val lookupsBefore = kit.gateway.lookupCalls.size
        kit.advance(ttl)

        kit.runTasks(expire)

        assertEquals(PaymentOrderStatus.CANCELED, kit.order(canceled.id).status)
        assertEquals(PaymentOrderStatus.PAID, kit.order(paid.id).status)
        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size, "the expiry did not ask the provider")
    }

    private fun withoutProvider(kit: PaymentLedgerKit) =
        OrderExpireTaskHandler(kit.store, PaymentProviderRouter(listOf(ScriptedGateway(providerId = "elsewhere")), kit.properties), kit.convergence)

    @Test
    fun `a pending order whose provider bean is missing closes without a mark once its deadline has passed and is retried before it`() {
        val kit = PaymentLedgerKit()
        val early = kit.create(key = "k1").order
        kit.advance(ttl.minusSeconds(1))
        assertFailsWith<PaymentTaskRetryException> { withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, early.id, 1, 12)) }
        assertEquals(PaymentOrderStatus.PENDING, kit.order(early.id).status)

        kit.advance(Duration.ofSeconds(1))
        withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, early.id, 2, 12))

        assertEquals(PaymentOrderStatus.EXPIRED, kit.order(early.id).status)
        assertNull(kit.order(early.id).reviewReason)
        assertTrue(kit.review.flagged.isEmpty())
    }

    @Test
    fun `a pending order with a received mark is not closed without its provider either - the job dies for a person`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order
        kit.store.inTransaction { tx -> kit.convergence.flagReview(tx, tx.lockOrder(order.id)!!, PaymentReviewReason.AMOUNT_MISMATCH, ConvergeContext(PaymentMode.TEST, ConvergeSource.RECONCILE)) }
        kit.advance(ttl)

        assertFailsWith<PaymentTaskPermanentException> { withoutProvider(kit).handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, order.id, 1, 12)) }

        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }
}
