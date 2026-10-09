package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.http.ExternalHttpNetworkException
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

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(3000, paid.paidAmount)
        assertEquals("HYUNDAI", paid.cardIssuer)
        assertEquals(1, kit.gateway.confirmCalls.size, "settling asks, it never confirms again")
        assertEquals(PaymentQueryAsserts.byPayment("pay_1"), kit.gateway.lookupCalls.single())
        assertEquals(PaymentTaskTypes.ORDER_RECONCILE, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single().task.type)
        assertEquals(dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single().state)
    }

    @Test
    fun `a confirm that timed out is settled as failed when the provider says ABORTED`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutNotApplied)
        kit.gateway.abort("pay_1")
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        val failed = kit.order(order.id)
        assertEquals(PaymentOrderStatus.FAILED, failed.status)
        assertEquals("PROVIDER_ABORTED", failed.failureCode)
        assertEquals(0, kit.listener.paid.size)
        assertEquals(1, kit.gateway.confirmCalls.size)
    }

    @Test
    fun `a confirm that timed out stays confirming while the provider is still in progress and settles when it is done`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutAppliedLater)
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        val stillWaiting = kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single()

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State.PENDING, stillWaiting.state, "the job is not released")
        assertEquals(1, stillWaiting.attempts)
        assertNull(kit.order(order.id).reviewReason)

        kit.gateway.settle("pay_1")
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State.DONE, stillWaiting.state)
    }

    @Test
    fun `the reconcile task does not run before its first delay`() {
        val kit = PaymentLedgerKit()
        timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.advance(firstDelay.minusSeconds(1))

        assertEquals(0, kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE)))
        assertEquals(0, kit.gateway.lookupCalls.size)
    }

    @Test
    fun `a lookup that fails keeps the job alive and the order untouched until the provider answers`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.advance(firstDelay)
        kit.gateway.failLookups(2)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(2, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single().attempts)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `when the attempts run out the order is flagged CONFIRM_UNRESOLVED once and the job dies`() {
        val kit = PaymentLedgerKit(PaymentProperties(reconcile = PaymentProperties.Reconcile(maxAttempts = 3)))
        val order = timedOut(kit, ConfirmScript.TimeoutNotApplied)
        kit.advance(firstDelay)

        repeat(3) { kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE)) }

        val flagged = kit.order(order.id)
        assertEquals(PaymentOrderStatus.CONFIRMING, flagged.status, "the money state is not guessed")
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, flagged.reviewReason)
        assertEquals(1, kit.tasks.dead(PaymentTaskTypes.ORDER_RECONCILE).size)
        assertEquals(listOf(order.id to PaymentReviewReason.CONFIRM_UNRESOLVED), kit.review.flagged)
    }

    @Test
    fun `a lookup that fails on the last attempt also flags the order`() {
        val kit = PaymentLedgerKit(PaymentProperties(reconcile = PaymentProperties.Reconcile(maxAttempts = 2)))
        val order = timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.advance(firstDelay)
        kit.gateway.failLookups(5)

        repeat(2) { kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE)) }

        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason)
        assertEquals(1, kit.tasks.dead(PaymentTaskTypes.ORDER_RECONCILE).size)
    }

    @Test
    fun `a provider total that differs dies permanently without retrying and is flagged`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied(total = PaymentAmount(2000, "KRW")))
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
        val task = kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single()
        assertEquals(dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State.DEAD, task.state)
        assertEquals(1, task.attempts)
    }

    @Test
    fun `a mode that changed since the order was created dies permanently and is flagged`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(1, kit.tasks.dead(PaymentTaskTypes.ORDER_RECONCILE).size)
    }

    @Test
    fun `a reconcile task for an order that is no longer confirming does nothing`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        val lookupsBefore = kit.gateway.lookupCalls.size // 승인이 카드 정보를 채우려고 한 번 물었다
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(lookupsBefore, kit.gateway.lookupCalls.size, "the task did not ask the provider")
        assertEquals(dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State.DONE, kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single().state)
    }

    @Test
    fun `a mode that changed is judged before any lookup - a lookup that would fail never gets the chance to loop the job`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutApplied())
        kit.gateway.mode = PaymentMode.LIVE
        kit.gateway.failLookups(5)
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        val task = kit.tasks.all(PaymentTaskTypes.ORDER_RECONCILE).single()
        assertEquals(dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks.State.DEAD, task.state)
        assertEquals(1, task.attempts, "no twelve futile attempts")
        assertEquals(0, kit.gateway.lookupCalls.size, "the other mode's key is never used to ask")
        assertEquals(listOf(order.id to PaymentReviewReason.MODE_MISMATCH), kit.review.flagged)
    }

    @Test
    fun `a mode that changed is judged before any lookup - a payment the other mode's key cannot see is not waited for`() {
        val kit = PaymentLedgerKit()
        val created = kit.create().order // 결제사에는 이 주문의 결제가 없다(인증을 안 했다)
        kit.gateway.script(ConfirmScript.ServerError)
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        kit.gateway.mode = PaymentMode.LIVE
        kit.advance(firstDelay)

        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(created.id).reviewReason)
        assertEquals(1, kit.tasks.dead(PaymentTaskTypes.ORDER_RECONCILE).size)
        assertEquals(0, kit.gateway.lookupCalls.size)
    }

    private fun confirmOnlyRouter(kit: PaymentLedgerKit): PaymentProviderRouter {
        val confirmOnly = object : PaymentConfirmGateway {
            override val providerId = "scripted"
            override fun confirm(request: PaymentConfirmRequest) = kit.gateway.confirm(request)
        }
        return PaymentProviderRouter(listOf(confirmOnly), kit.properties)
    }

    @Test
    fun `a provider without the lookup capability flags the confirming order CONFIRM_UNRESOLVED and the job dies permanently`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        val handler = OrderReconcileTaskHandler(kit.store, confirmOnlyRouter(kit), kit.convergence)
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)

        assertFailsWith<PaymentTaskPermanentException> { handler.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, created.id, 1, 12)) }

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status)
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(created.id).reviewReason, "money may have moved - the review list must show it")
        assertEquals(listOf(created.id to PaymentReviewReason.CONFIRM_UNRESOLVED), kit.review.flagged)
    }

    @Test
    fun `a provider bean that is missing is a configuration state - the job retries and flags only on its last attempt`() {
        val kit = PaymentLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        val elsewhere = ScriptedGateway(providerId = "elsewhere")
        val handler = OrderReconcileTaskHandler(kit.store, PaymentProviderRouter(listOf(elsewhere), kit.properties), kit.convergence)

        assertFailsWith<PaymentTaskRetryException> { handler.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, created.id, 1, 12)) }
        assertNull(kit.order(created.id).reviewReason, "an early attempt leaves the row alone - the configuration may be fixed")

        assertFailsWith<PaymentTaskRetryException> { handler.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, created.id, 12, 12)) }
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(created.id).reviewReason)
    }

    @Test
    fun `handler exceptions carry our sentence and the order id - never the provider's words`() {
        val kit = PaymentLedgerKit()
        val order = timedOut(kit, ConfirmScript.TimeoutNotApplied)
        val secret = "card 4111-1111-1111-1111 of Kim Minsu kim@example.com"
        kit.gateway.failLookups(1) { ExternalHttpNetworkException("scripted", "GET", URI.create("https://p.example.test/x"), IOException(secret)) }

        val retry = assertFailsWith<PaymentTaskRetryException> { kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12)) }
        val notSettled = assertFailsWith<PaymentTaskRetryException> { kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, order.id, 1, 12)) }

        listOf(retry, notSettled).forEach { e ->
            assertTrue(e.message!!.contains(order.id), e.message)
            listOf("4111", "Kim", "kim@", "scripted", "IOException", "dev.sumin").forEach { assertTrue(!e.message!!.contains(it), "$it in ${e.message}") }
        }
    }

    @Test
    fun `a missing order is not an error - the row may have been cleaned up`() {
        val kit = PaymentLedgerKit()

        kit.reconcile.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_RECONCILE, "ord_" + "f".repeat(32), 1, 12))
        kit.expire.handle(PaymentTaskRun(PaymentTaskTypes.ORDER_EXPIRE, "ord_" + "f".repeat(32), 1, 12))

        assertEquals(0, kit.gateway.lookupCalls.size)
    }

    private object PaymentQueryAsserts {
        fun byPayment(id: String) = PaymentLookupQuery(providerPaymentId = id, orderId = null)
    }
}
