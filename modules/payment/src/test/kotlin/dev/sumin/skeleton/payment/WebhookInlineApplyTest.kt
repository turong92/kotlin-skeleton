package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 웹훅을 받은 요청 안에서 곧바로 반영해 보는 길(`webhook.inline`). 같은 웹훅을 인라인과 일꾼이 두 번 반영하지 않고,
 * 느리거나 실패해도 결제사 응답(수신)은 늦어지지 않으며 일꾼이 지금처럼 이어받는다.
 */
class WebhookInlineApplyTest {
    private fun kit(budget: Duration = Duration.ofSeconds(5)) = PaymentLedgerKit(
        PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, inline = PaymentProperties.Webhook.Inline(enabled = true, timeBudget = budget)),
            subscription = PaymentProperties.Subscription(enabled = true),
        ),
    )

    /** 승인 응답을 못 받은 주문 - 결제사는 이미 승인했다. 웹훅이 오면 조회로 끝난다. */
    private fun confirmingButPaid(kit: PaymentLedgerKit): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        return kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
    }

    @Test
    fun `the webhook is applied inside the receiving call and the worker later finds nothing left to do`() {
        val kit = kit()
        val order = confirmingButPaid(kit)

        val receipt = kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        assertEquals(WebhookReceipt.STORED, receipt)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status, "applied before the call returned")
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.listWebhookEvents(null, null, 10).single().status)
        val lookups = kit.gateway.lookupCalls.size

        assertEquals(0, kit.runWebhooks(), "the job was finished by the inline attempt - not picked again")
        assertEquals(lookups, kit.gateway.lookupCalls.size, "no second provider lookup")
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `a failing inline attempt still answers the provider and leaves the job to the worker`() {
        val kit = kit()
        val order = confirmingButPaid(kit)
        kit.gateway.failLookups(1)

        val receipt = kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        assertEquals(WebhookReceipt.STORED, receipt, "the delivery is acknowledged as before")
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(1, kit.runWebhooks(), "the worker takes it, as it did before the inline path existed")
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `a slow inline attempt does not hold the answer and the job is not applied a second time by the worker`() {
        val kit = kit(budget = Duration.ofMillis(150))
        val order = confirmingButPaid(kit)
        val inLookup = CountDownLatch(1)
        val release = CountDownLatch(1)
        kit.gateway.afterLookup = {
            inLookup.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        val started = System.nanoTime()
        val receipt = kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertEquals(WebhookReceipt.STORED, receipt)
        assertTrue(inLookup.await(2, TimeUnit.SECONDS), "the inline attempt is running")
        assertTrue(tookMillis < 2_000, "answered after the time budget, not after the slow lookup: ${tookMillis}ms")
        assertEquals(0, kit.runWebhooks(), "the job is held by the inline attempt - the worker leaves it alone")

        kit.gateway.afterLookup = null
        release.countDown()
        val deadline = System.nanoTime() + 5_000_000_000
        while (kit.order(order.id).status != PaymentOrderStatus.PAID && System.nanoTime() < deadline) Thread.sleep(20)

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(1, kit.gateway.lookupCalls.size, "one lookup in all")
    }

    @Test
    fun `with the switch off nothing is applied inside the call`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaid(kit)

        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        assertEquals(1, kit.runWebhooks())
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }
}
