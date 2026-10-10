package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpNetworkException
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.io.IOException
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderReconcileTest {
    private val firstDelay = Duration.ofSeconds(60)
    private val reconcile = setOf(PaymentTaskTypes.ORDER_RECONCILE)

    /** 승인 응답을 못 받은 주문 하나 — 결제는 결제사에서 인증까지 끝났다. */
    private fun timedOut(kit: PaymentLedgerKit, script: ConfirmScript): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(script)
        val result = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, result.status)
        return result
    }

    @Test
    fun `a confirm that timed out is settled as paid when the provider says DONE`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.advance(firstDelay)

        kit.runTasks(reconcile)

        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals(1, kit.gateway.confirmCalls.size, "settling asks, it never confirms again")
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single().state)
    }

    @Test
    fun `a confirm that timed out is settled as failed when the provider says ABORTED`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutNotApplied)
        kit.gateway.abort("pay_1")
        kit.advance(firstDelay)

        kit.runTasks(reconcile)

        assertEquals(PaymentOrderStatus.FAILED, kit.order(order.id).status)
        assertEquals(0, kit.listener.paid.size)
        assertEquals(1, kit.gateway.confirmCalls.size)
    }

    @Test
    fun `a confirm that timed out stays confirming while the provider is still in progress and settles when it is done`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutAppliedLater)
        kit.advance(firstDelay)

        kit.runTasks(reconcile)
        val job = kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single()

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(State.PENDING, job.state, "the job is not released")
        assertNull(kit.order(order.id).reviewReason)

        kit.gateway.settle("pay_1")
        kit.runTasks(reconcile)

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `when the attempts run out the order is flagged CONFIRM_UNRESOLVED and the job dies - the money state is not guessed`() {
        val kit = PaymentLedgerKit(PaymentProperties(reconcile = PaymentProperties.Reconcile(maxAttempts = 3)))
        val order = timedOut(kit, ConfirmScript.TimeoutNotApplied)
        kit.advance(firstDelay)

        repeat(3) { kit.runTasks(reconcile) }

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason)
        assertEquals(1, kit.tasks.dead(PaymentTaskTypes.ORDER_RECONCILE).size)
    }

    @Test
    fun `a provider total that differs dies permanently without retrying and is flagged`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied(total = PaymentAmount(2000, "KRW")))
        kit.advance(firstDelay)

        kit.runTasks(reconcile)

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
        val job = kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single()
        assertEquals(State.DEAD, job.state)
        assertEquals(1, job.attempts)
    }

    @Test
    fun `a reconcile task for an order that is no longer confirming does not ask the provider`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        val lookupsBefore = kit.gateway.lookupCalls.size
        kit.advance(firstDelay)

        kit.runTasks(reconcile)

        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size)
        assertEquals(State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single().state)
    }

    @Test
    fun `a mode that changed is judged before any lookup - the other mode's key is never used to ask`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.gateway.mode = PaymentMode.LIVE
        kit.gateway.failLookups(5)
        kit.advance(firstDelay)

        kit.runTasks(reconcile)

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        val job = kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single()
        assertEquals(State.DEAD, job.state)
        assertEquals(1, job.attempts, "no twelve futile attempts")
        assertEquals(0, kit.gateway.lookupCalls.size)
    }

    @Test
    fun `a provider without the lookup capability dies permanently flagged, a provider that is missing retries and flags only on its last attempt`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        val run = { attempt: Int -> PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, created.id, attempt, 12) }

        val confirmOnly = object : PaymentConfirmGateway {
            override val providerId = "scripted"
            override fun confirm(request: PaymentConfirmRequest) = kit.gateway.confirm(request)
        }
        val noLookup = OrderReconcileTaskHandler(kit.store, PaymentProviderRouter(listOf(confirmOnly), kit.properties), kit.convergence)
        val missing = OrderReconcileTaskHandler(kit.store, PaymentProviderRouter(listOf(ScriptedGateway(providerId = "elsewhere")), kit.properties), kit.convergence)

        assertFailsWith<PaymentTaskRetryException> { missing.handle(run(1)) }
        assertNull(kit.order(created.id).reviewReason, "an early attempt leaves the row alone - the configuration may be fixed")
        assertFailsWith<PaymentTaskRetryException> { missing.handle(run(12)) }
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(created.id).reviewReason)

        assertFailsWith<PaymentTaskPermanentException> { noLookup.handle(run(1)) }
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
    }

    @Test
    fun `handler exceptions carry our sentence and the order id - never the provider's words`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutNotApplied)
        val secret = "card 4111-1111-1111-1111 of Kim Minsu kim@example.com"
        kit.gateway.failLookups(1) { ExternalHttpNetworkException("scripted", "GET", URI.create("https://p.example.test/x"), IOException(secret)) }

        val retry = assertFailsWith<PaymentTaskRetryException> { kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12)) }

        assertTrue(retry.message!!.contains(order.id), retry.message)
        listOf("4111", "Kim", "kim@", "scripted", "IOException", "dev.sumin").forEach { assertTrue(it !in retry.message!!, "$it in ${retry.message}") }
    }
}
