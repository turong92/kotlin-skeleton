package dev.sumin.skeleton.payment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

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

    private fun windowOf(kit: PaymentLedgerKit, at: java.time.Instant = kit.time.now()): Long = at.toEpochMilli() / Duration.ofMinutes(10).toMillis()

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
    fun `a resend of a stored event is no new event - it still counts as a duplicate while the order is at the limit`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 31)))

        val again = kit.deliver(delivery(kit, order.id, 7))

        assertEquals(WebhookReceipt.DUPLICATE, again)
        assertEquals(2, rows(kit).single { it.eventKey == "evt-7" }.deliveries)
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
    fun `an order that has no payment key yet is counted too - whatever key the body names`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated() // 아직 승인 전 - 결제 키가 장부에 없다

        val receipts = (1..40).map { kit.deliver(delivery(kit, order.id, it, paymentKey = "pay_forged_$it")) }

        assertEquals(30, receipts.count { it == WebhookReceipt.STORED })
        assertEquals(10, receipts.count { it == WebhookReceipt.LIMITED })
    }

    @Test
    fun `after the window the order is accepted again - the rows that fell out of the window no longer count`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 31)))

        kit.advance(Duration.ofMinutes(10).plusSeconds(1))
        val after = kit.deliver(delivery(kit, order.id, 32))

        assertEquals(WebhookReceipt.STORED, after)
        assertEquals(31, stored(kit).size)
    }

    @Test
    fun `another order is not affected by a saturated one`() {
        val kit = PaymentLedgerKit()
        val noisy = kit.paid(accountId = "acc_1", key = "k-1", paymentKey = "pay_1")
        val quiet = kit.paid(accountId = "acc_2", key = "k-2", paymentKey = "pay_2")
        (1..31).forEach { kit.deliver(delivery(kit, noisy.id, it)) }

        val receipt = kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-quiet", paymentKey = "pay_2", orderId = quiet.id))

        assertEquals(WebhookReceipt.STORED, receipt)
    }

    @Test
    fun `the limit comes from the properties`() {
        val kit = PaymentLedgerKit(
            PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, perOrderLimit = PaymentProperties.Webhook.PerOrderLimit(capacity = 2, window = Duration.ofMinutes(1)))),
        )
        val order = kit.paid()

        val receipts = (1..3).map { kit.deliver(delivery(kit, order.id, it)) }
        kit.advance(Duration.ofMinutes(1).plusSeconds(1))

        assertEquals(listOf(WebhookReceipt.STORED, WebhookReceipt.STORED, WebhookReceipt.LIMITED), receipts)
        assertEquals(WebhookReceipt.STORED, kit.deliver(delivery(kit, order.id, 4)))
    }

    @Test
    fun `a signal past the limit is not dropped - one marker row per order and window, one job deferred to the end of the window`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        val jobsBefore = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size
        val window = windowOf(kit)

        val receipts = (31..37).map { kit.deliver(delivery(kit, order.id, it)) }

        assertEquals(List(7) { WebhookReceipt.LIMITED }, receipts)
        val marker = markers(kit).single()
        assertEquals("limited:${order.id}:$window", marker.eventKey)
        assertEquals(WebhookMarkers.EVENT_TYPE, marker.eventType)
        assertEquals(order.id, marker.orderId)
        assertEquals(PaymentWebhookStatus.RECEIVED, marker.status)
        assertEquals(7, marker.deliveries, "deliveries = how many signals hit the limit in that window")
        assertEquals(30, stored(kit).size, "the marker is not a delivery - it does not count against the limit")
        val job = kit.tasks.all(PaymentTaskTypes.WEBHOOK).drop(jobsBefore).single()
        assertEquals(marker.id.toString(), job.task.subjectId)
        assertEquals(java.time.Instant.ofEpochMilli((window + 1) * Duration.ofMinutes(10).toMillis()), job.task.runAt, "deferred to the end of the window")
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
    fun `an operator who retries the marker does not swallow the rest of the window - the next limited signal re-arms the marker and the end-of-window job`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        kit.runWebhooks()
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 31)))
        val marker = markers(kit).single()
        kit.inbox.retry(marker.id) // 창 한가운데에서 운영자가 표식 줄의 「다시 처리」를 누른다
        kit.runWebhooks()
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(marker.id)!!.status)
        kit.gateway.consoleCancel("pay_1", order.amount.amount) // 그 뒤 결제사 콘솔에서 취소 - 창은 아직 가득이다
        val markerJobs = { kit.tasks.pending(PaymentTaskTypes.WEBHOOK).filter { it.task.subjectId == marker.id.toString() } }
        val before = markerJobs().size

        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 32, paymentKey = "pay_1")))

        assertEquals(PaymentWebhookStatus.RECEIVED, kit.store.findWebhookEvent(marker.id)!!.status, "the marker is back to waiting")
        assertEquals(before + 1, markerJobs().size, "one more job at the end of the window")
        val window = windowOf(kit)
        assertEquals(java.time.Instant.ofEpochMilli((window + 1) * Duration.ofMinutes(10).toMillis()), markerJobs().last().task.runAt)
        (33..40).forEach { assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, it))) }
        assertEquals(before + 1, markerJobs().size, "more limited signals while the marker waits add no job")

        kit.advance(Duration.ofMinutes(10))
        kit.runWebhooks()

        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order.id).status, "the end-of-window lookup put the cancel on the ledger")
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(marker.id)!!.status)
    }

    @Test
    fun `the next window gets its own marker`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 31)))
        val first = windowOf(kit)

        kit.advance(Duration.ofMinutes(10).plusSeconds(1))
        (32..61).forEach { assertEquals(WebhookReceipt.STORED, kit.deliver(delivery(kit, order.id, it))) }
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 62)))
        assertEquals(WebhookReceipt.LIMITED, kit.deliver(delivery(kit, order.id, 63)))

        val keys = markers(kit).associate { it.eventKey to it.deliveries }
        assertEquals(mapOf("limited:${order.id}:$first" to 1, "limited:${order.id}:${windowOf(kit)}" to 2), keys)
        assertTrue(windowOf(kit) > first)
    }

    @Test
    fun `a marker row never takes a place in the count - the window frees up exactly when the real rows fall out`() {
        val kit = PaymentLedgerKit(
            PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, perOrderLimit = PaymentProperties.Webhook.PerOrderLimit(capacity = 2, window = Duration.ofMinutes(1)))),
        )
        val order = kit.paid()
        val receipts = (1..5).map { kit.deliver(delivery(kit, order.id, it)) }

        assertEquals(listOf(WebhookReceipt.STORED, WebhookReceipt.STORED, WebhookReceipt.LIMITED, WebhookReceipt.LIMITED, WebhookReceipt.LIMITED), receipts)
        assertEquals(2, stored(kit).size)
        assertEquals(1, markers(kit).size)
        kit.advance(Duration.ofMinutes(1).plusSeconds(1))
        assertEquals(listOf(WebhookReceipt.STORED, WebhookReceipt.STORED, WebhookReceipt.LIMITED), (6..8).map { kit.deliver(delivery(kit, order.id, it)) })
    }

    @Test
    fun `the marker shows in the operator list under a key that starts with limited and the order id`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        (1..30).forEach { kit.deliver(delivery(kit, order.id, it)) }
        kit.deliver(delivery(kit, order.id, 31))

        assertTrue(kit.inbox.list(null, null, 100).values.any { it.eventKey.startsWith("limited:${order.id}:") }, "the operator can see which order hit the limit")
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

    @Test
    fun `every kind of receipt leaves one log line with the kind only - no order id, payment key, event key or token`() {
        val kit = PaymentLedgerKit(
            PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, perOrderLimit = PaymentProperties.Webhook.PerOrderLimit(capacity = 1, window = Duration.ofMinutes(1)))),
        )
        val order = kit.paid()
        val logger = LoggerFactory.getLogger(PaymentWebhookInbox::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            kit.deliver(delivery(kit, order.id, 1)) // STORED
            kit.deliver(delivery(kit, order.id, 1)) // DUPLICATE
            kit.deliver(delivery(kit, order.id, 2)) // LIMITED
            kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-ghost", paymentKey = "pay_ghost", orderId = "ord_" + "e".repeat(32))) // UNKNOWN_ORDER
            kit.deliver(kit.gateway.webhookDelivery(eventType = "DEPOSIT_CALLBACK", eventKey = "evt-dep")) // UNSUPPORTED
            kit.runWebhooks()
            kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(stored(kit).single().id)).copy(status = PaymentWebhookStatus.DEAD)) }
            kit.deliver(delivery(kit, order.id, 1)) // REQUEUED
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }

        val lines = appender.list.map { it.formattedMessage }
        listOf("STORED", "DUPLICATE", "LIMITED", "UNKNOWN_ORDER", "UNSUPPORTED", "REQUEUED").forEach { kind -> assertTrue(lines.any { it.contains("result=$kind") }, "no line for $kind: $lines") }
        val all = lines.joinToString("\n")
        listOf(order.id, "pay_1", "pay_ghost", "evt-1", "evt-ghost", PaymentLedgerKit.WEBHOOK_TOKEN).forEach { assertFalse(all.contains(it), "the log carries $it:\n$all") }
        assertTrue(appender.list.single { it.formattedMessage.contains("result=LIMITED") }.level == Level.WARN)
    }
}
