package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 중도 해지에서 깨지면 사고 나는 것: 환불 합계가 낸 돈을 못 넘는다 · 두 번 눌러도 환불은 한 번 · 갱신 과금 중에는 막힌다 · 정책이 닫혔으면 아무것도 안 바뀐다. 메모리 저장소 + 빌링 대역. */
class SubscriptionCancelNowTest {
    private fun newKit(usage: SubscriptionUsageCheck = SubscriptionUsageCheck.NEVER_USED) = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, Charge.PRORATED_DIFFERENCE, 0)),
        ),
        usage = usage,
    )

    private fun PaymentLedgerKit.active(country: String? = "KR"): PaymentSubscription {
        val id = subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), country).subscription.id
        return subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
    }

    private fun PaymentLedgerKit.sub(id: String) = checkNotNull(store.findSubscription(id))

    @Test
    fun `cancelling in the middle of the period ends the subscription at once, refunds the unused rest minus the penalty through the ledger and removes the billing key`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(10))
        val preview = kit.cancellations.preview("acc_1", active.id)
        assertTrue(preview.quote.open)

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertEquals(preview.quote.refund, result.quote.refund, "nothing moved between the preview and the press")
        assertTrue(result.quote.refund in 2_100..2_500, "about 20 of 30 days of 3,900 minus 10%, was ${result.quote.refund}")
        val ended = kit.sub(active.id)
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.USER_CANCELED_NOW, ended.endReason)
        assertNull(ended.activeKey)
        assertEquals(result.quote.refund, kit.gateway.refundCalls.single().amount.amount)
        val order = kit.store.listSubscriptionOrders(active.id, 5).single()
        assertEquals(result.quote.refund, kit.order(order.id).refundedAmount)
        assertEquals(listOf(CancelRefundStatus.SUCCEEDED), result.refunds.map { it.status })
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_REVOKE))
        assertEquals(1, kit.gateway.revokeCalls.size)
        assertNull(kit.sub(active.id).billingToken)
    }

    @Test
    fun `inside seven days every payment of the period comes back in full and the total never passes what was paid`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(1))
        kit.planChanges.change("acc_1", active.id, "plus", 9_999, IdempotencyKey("chg-1"))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        val paid = kit.store.listSubscriptionOrders(active.id, 5).sumOf { it.paidAmount ?: it.amount.amount }
        assertEquals(2, kit.store.listSubscriptionOrders(active.id, 5).size)

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertTrue(result.quote.withdrawal)
        assertEquals(paid, result.quote.refund)
        assertEquals(paid, kit.gateway.refundCalls.sumOf { it.amount.amount })
        kit.store.listSubscriptionOrders(active.id, 5).forEach { assertEquals(PaymentOrderStatus.REFUNDED, kit.order(it.id).status) }
        assertEquals(SubscriptionEndReason.USER_CANCELED_NOW, kit.sub(active.id).endReason, "the full refund of the first payment does not rewrite why it ended")
    }

    @Test
    fun `money already refunded on the period's payment is not paid back a second time`() {
        val kit = newKit()
        val active = kit.active()
        val order = kit.store.listSubscriptionOrders(active.id, 5).single()
        kit.refunds.refund("op_1", order.id, "OPERATOR", 1_000, IdempotencyKey("op-1"))
        kit.time.advance(Duration.ofDays(1))

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertEquals(2_900, result.quote.refund)
        assertEquals(3_900, kit.order(order.id).refundedAmount)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status)
    }

    @Test
    fun `pressing again sends no second refund and answers the same`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(10))

        val first = kit.cancellations.cancelNow("acc_1", active.id)
        val second = kit.cancellations.cancelNow("acc_1", active.id)

        assertEquals(1, kit.gateway.refundCalls.size)
        assertEquals(first.quote.refund, second.quote.refund)
        assertEquals(first.refunds.map { it.orderId to it.amount }, second.refunds.map { it.orderId to it.amount })
        assertEquals(first.quote.refund, kit.order(first.refunds.single().orderId).refundedAmount)
    }

    @Test
    fun `a renewal charge that is still unanswered blocks the cancel and nothing changes`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.set(checkNotNull(active.currentPeriodEnd).plusSeconds(1))
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))

        assertFailsWith<PaymentSubscriptionChargeInProgressException> { kit.cancellations.cancelNow("acc_1", active.id) }

        assertEquals(SubscriptionStatus.ACTIVE, kit.sub(active.id).status)
        assertEquals(0, kit.gateway.refundCalls.size)
    }

    @Test
    fun `a country whose policy keeps the mid-term cancel closed gets no preview amount and a refusal`() {
        val kit = newKit()
        val active = kit.active(country = null)

        assertEquals(false, kit.cancellations.preview("acc_1", active.id).quote.open)
        assertFailsWith<PaymentSubscriptionCancelNotAllowedException> { kit.cancellations.cancelNow("acc_1", active.id) }

        assertEquals(SubscriptionStatus.ACTIVE, kit.sub(active.id).status)
        assertEquals(0, kit.gateway.refundCalls.size)
    }

    @Test
    fun `somebody else's subscription looks like none`() {
        val kit = newKit()
        val active = kit.active()

        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.cancellations.preview("acc_2", active.id) }
        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.cancellations.cancelNow("acc_2", active.id) }
    }

    @Test
    fun `the app can say it was used and the withdrawal then does not apply`() {
        val kit = newKit(usage = SubscriptionUsageCheck { _, _, _ -> true })
        val active = kit.active()
        kit.time.advance(Duration.ofDays(1))

        val quote = kit.cancellations.preview("acc_1", active.id).quote

        assertEquals(false, quote.withdrawal)
        assertTrue(quote.refund < 3_900)
    }

    @Test
    fun `a refund the provider could not be asked about is sent by the retry task exactly once`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(1))
        kit.gateway.failLookups(1)

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertEquals(listOf(CancelRefundStatus.NOT_SENT), result.refunds.map { it.status })
        assertEquals(SubscriptionStatus.ENDED, kit.sub(active.id).status)
        assertEquals(0, kit.gateway.refundCalls.size)

        kit.time.advance(Duration.ofHours(1))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_CANCEL_REFUND))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_CANCEL_REFUND))

        assertEquals(1, kit.gateway.refundCalls.size)
        assertEquals(3_900, kit.order(result.refunds.single().orderId).refundedAmount)
    }

    @Test
    fun `a refund the provider declines is reported and the subscription stays ended`() {
        val kit = newKit()
        val active = kit.active()
        kit.time.advance(Duration.ofDays(1))
        kit.gateway.scriptRefund(RefundScript.Decline())

        val result = kit.cancellations.cancelNow("acc_1", active.id)

        assertEquals(listOf(CancelRefundStatus.FAILED), result.refunds.map { it.status })
        assertEquals(SubscriptionStatus.ENDED, kit.sub(active.id).status)
        assertEquals(1, kit.gateway.refundCalls.size)
    }
}
