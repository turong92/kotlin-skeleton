package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 새 이벤트 키에 거는 주문 단위 창 상한(`webhook.per-order-limit`) — 호출자가 정하는 전달 id 로 새 줄 · 새 작업 · 결제사 조회를 마음대로 만들지 못하게 한다.
 * 넘으면 저장 없이 받은 것으로 답한다(`LIMITED`). 같은 이벤트의 재전송은 이 상한을 타지 않는다(줄 · 작업이 늘지 않는다).
 */
class WebhookPerOrderLimitTest {
    private fun delivery(kit: PaymentLedgerKit, orderId: String, n: Int, paymentKey: String = "pay_1") =
        kit.gateway.webhookDelivery(eventKey = "evt-$n", paymentKey = paymentKey, orderId = orderId)

    private fun rows(kit: PaymentLedgerKit) = kit.store.listWebhookEvents(null, null, 100)

    /** 진짜 배달로 저장된 줄 — 상한에 걸린 표식 줄은 빠진다 */
    private fun stored(kit: PaymentLedgerKit) = rows(kit).filter { it.resourceType == "PAYMENT" }

    private fun markers(kit: PaymentLedgerKit) = rows(kit).filter { it.resourceType == WebhookMarkers.RESOURCE_TYPE }

    @Test
    fun `thirty new events of one order are stored and the thirty-first is answered but not stored, not queued and never looked up`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        val receipts = (1..30).map { kit.deliver(delivery(kit, order.id, it)) }
        kit.runWebhooks()
        val lookups = kit.gateway.lookupCalls.size
        val jobs = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

        val limited = kit.deliver(delivery(kit, order.id, 31))
        kit.runWebhooks()

        assertEquals(List(30) { WebhookReceipt.STORED }, receipts)
        assertEquals(WebhookReceipt.LIMITED, limited)
        assertEquals(30, stored(kit).size)
        assertTrue(rows(kit).none { it.eventKey == "evt-31" })
        // 흔적은 표식 줄 하나와 창 끝으로 미룬 작업 하나뿐이다 — 지금 도는 작업도 결제사 호출도 없다
        assertEquals(jobs + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size, "only the deferred marker job")
        assertEquals(lookups, kit.gateway.lookupCalls.size, "no provider call now")
    }

    @Test
    fun `changing the payment key in the body does not buy a new allowance - the order is counted by its own payment`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid() // 결제 키 pay_1
        val receipts = (1..40).map { kit.deliver(delivery(kit, order.id, it, paymentKey = "pay_forged_$it")) }

        assertEquals(30, receipts.count { it == WebhookReceipt.STORED })
        assertEquals(10, receipts.count { it == WebhookReceipt.LIMITED })
        assertEquals(setOf("pay_1"), stored(kit).map { it.resourceId }.toSet(), "the row names the order's own payment, whatever the body claimed")
    }

    @Test
    fun `a real cancel that arrives while the window is full is still reconciled by the lookup at the end of the window`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        kit.runWebhooks()
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        kit.gateway.consoleCancel("pay_1", order.amount.amount) // 결제사 콘솔에서 취소
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 31, paymentKey = "pay_1"))) // 취소를 알리는 진짜 신호 - 창이 가득이라 저장되지 않는다
        kit.runWebhooks()
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status, "nothing runs before the end of the window")

        kit.advance(Duration.ofMinutes(10)) // 창 끝을 지난다
        kit.runWebhooks()

        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status, "the deferred lookup put the cancel on the ledger")
        assertEquals(PaymentWebhookStatus.PROCESSED, markers(kit).single().status)
    }

    @Test
    fun `an event key that looks like a marker is refused - a real delivery cannot take the marker's place`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        assertFailsWith<PaymentWebhookRejectedException> { kit.deliver(kit.gateway.webhookDelivery(eventKey = "limited:${order.id}:1", paymentKey = "pay_1", orderId = order.id)) }
        assertEquals(0, rows(kit).size)
    }

    @Test
    fun `no insert order race - the count is taken after the order is locked, in the same transaction as the insert`() {
        val kit = PaymentLedgerKit(
            PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, perOrderLimit = PaymentProperties.Webhook.PerOrderLimit(capacity = 5, window = Duration.ofMinutes(10)))),
        )
        val order = kit.paid()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(16)
        try {
            val receipts = (1..64).map { n -> pool.submit<WebhookReceipt> { kit.deliver(delivery(kit, order.id, n)) } }.map { it.get() }

            assertEquals(5, receipts.count { it == WebhookReceipt.STORED }, "exactly the capacity - not a line more")
            assertEquals(59, receipts.count { it == WebhookReceipt.LIMITED })
            assertEquals(5, stored(kit).size)
            assertEquals(1, markers(kit).size)
            assertEquals(59, markers(kit).single().deliveries)
        } finally {
            pool.shutdownNow()
        }
    }
}
