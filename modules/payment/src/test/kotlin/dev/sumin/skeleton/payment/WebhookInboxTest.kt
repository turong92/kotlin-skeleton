package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 스펙 §3 의 7 · 8 · 9 · 10 · 11 과 §6.5 — 웹훅은 신호일 뿐이고 사실은 늘 결제사 조회에서 온다. */
class WebhookInboxTest {
    private val firstDelay = Duration.ofSeconds(60)

    private fun inbox(kit: PaymentLedgerKit) = kit.store.listWebhookEvents(null, null, 100)

    /** 승인 응답을 못 받은 주문 하나 — 결제사는 이미 승인했다(`pay_1`, 3,000원). */
    private fun confirmingButPaidAtProvider(kit: PaymentLedgerKit): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        val order = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, order.status)
        return order
    }

    // ---- 스펙 §3 7: 위조 ----

    @Test
    fun `a forged DONE for an unpaid order changes nothing`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated() // 결제창 인증만 끝났다 — 결제사는 아직 진행 중
        val before = kit.order(order.id)

        val receipt = kit.deliver(kit.gateway.webhookDelivery(paymentKey = "pay_1", orderId = order.id, claimedStatus = "DONE"))
        kit.runWebhooks()

        assertEquals(WebhookReceipt.STORED, receipt)
        assertEquals(before, kit.order(order.id), "the row is exactly as it was")
        assertEquals(0, kit.listener.paid.size)
        assertEquals(0, kit.gateway.confirmCalls.size)
        assertEquals(1, kit.gateway.lookupCalls.size, "the claim was checked with the provider, not believed")
        assertEquals(PaymentWebhookStatus.PROCESSED, inbox(kit).single().status)
    }

    @Test
    fun `an unknown order id is acknowledged and not stored`() {
        val kit = PaymentLedgerKit()
        kit.createAuthenticated()

        val byOrder = kit.deliver(kit.gateway.webhookDelivery(eventKey = "e1", paymentKey = "pay_nope", orderId = "ord_" + "f".repeat(32)))
        val byKeyOnly = kit.deliver(kit.gateway.webhookDelivery(eventKey = "e2", paymentKey = "pay_nope", orderId = null))

        assertEquals(WebhookReceipt.UNKNOWN_ORDER, byOrder)
        assertEquals(WebhookReceipt.UNKNOWN_ORDER, byKeyOnly)
        assertEquals(emptyList(), inbox(kit))
        assertEquals(0, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size)
        assertEquals(0, kit.gateway.lookupCalls.size, "an unknown order costs no provider call")
    }

    @Test
    fun `a wrong path token is 404 - and so is an unknown provider, a missing token, a provider without webhooks and a closed route`() {
        val kit = PaymentLedgerKit()

        listOf("wrong-token", "", null, PaymentLedgerKit.WEBHOOK_TOKEN + "x", PaymentLedgerKit.WEBHOOK_TOKEN.dropLast(1)).forEach { token ->
            assertFailsWith<PaymentWebhookNotFoundException>("token $token") { kit.inbox.admit("scripted", token) }
        }
        assertFailsWith<PaymentWebhookNotFoundException> { kit.inbox.admit("nope", PaymentLedgerKit.WEBHOOK_TOKEN) }
        assertNotNull(kit.inbox.admit("SCRIPTED ", PaymentLedgerKit.WEBHOOK_TOKEN), "the right token opens it, the provider id is normalised")

        val closed = PaymentLedgerKit(PaymentProperties()) // 비밀 조각을 정하지 않은 앱
        listOf("", null, "anything").forEach { token ->
            assertFailsWith<PaymentWebhookNotFoundException>("an unsigned provider with no configured token is closed: $token") { closed.inbox.admit("scripted", token) }
        }
    }

    @Test
    fun `a provider that has no webhook capability is the same not-found - the capability exception is not told apart`() {
        val kit = PaymentLedgerKit()
        val lookupOnly = object : PaymentLookupGateway {
            override val providerId = "lookup-only"
            override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? = null
        }
        val inbox = PaymentWebhookInbox(kit.store, kit.tasks, PaymentProviderRouter(listOf(kit.gateway, lookupOnly), kit.properties), kit.writer, kit.properties)

        val withoutCapability = assertFailsWith<PaymentWebhookNotFoundException> { inbox.admit("lookup-only", PaymentLedgerKit.WEBHOOK_TOKEN) }
        val unknown = assertFailsWith<PaymentWebhookNotFoundException> { inbox.admit("nope", PaymentLedgerKit.WEBHOOK_TOKEN) }

        assertEquals(unknown.message, withoutCapability.message)
        assertEquals(unknown.errorCode, withoutCapability.errorCode)
        assertNotNull(inbox.admit("scripted", PaymentLedgerKit.WEBHOOK_TOKEN), "the provider that can is still reached")
    }

    @Test
    fun `a provider that signs its webhooks needs no path token`() {
        val kit = PaymentLedgerKit(PaymentProperties())
        kit.gateway.webhookSigned = true
        val order = kit.createAuthenticated()

        val gateway = kit.inbox.admit("scripted", null)

        assertFailsWith<PaymentWebhookRejectedException> { kit.inbox.receive(gateway, kit.gateway.webhookDelivery(orderId = order.id, signature = "forged")) }
        assertFailsWith<PaymentWebhookRejectedException> { kit.inbox.receive(gateway, kit.gateway.webhookDelivery(orderId = order.id)) }
        assertEquals(emptyList(), inbox(kit), "a refused delivery stores nothing")
        assertEquals(WebhookReceipt.STORED, kit.inbox.receive(gateway, kit.gateway.webhookDelivery(orderId = order.id, signature = "valid")))
    }

    @Test
    fun `an event that is not a payment event is acknowledged and not stored`() {
        val kit = PaymentLedgerKit()
        kit.createAuthenticated()

        val receipt = kit.deliver(kit.gateway.webhookDelivery(eventType = "DEPOSIT_CALLBACK"))

        assertEquals(WebhookReceipt.UNSUPPORTED, receipt)
        assertEquals(emptyList(), inbox(kit))
    }

    @Test
    fun `an order is recognised by its provider payment id when the webhook carries no order id`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)

        val receipt = kit.deliver(kit.gateway.webhookDelivery(paymentKey = "pay_1", orderId = null))

        assertEquals(WebhookReceipt.STORED, receipt)
        assertEquals(order.id, inbox(kit).single().orderId, "the row stores our own order id")
    }

    @Test
    fun `the row keeps the delivery's identifiers and a hash of the body - never the body, never what the body claimed`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        val delivery = kit.gateway.webhookDelivery(eventKey = "evt-ids", paymentKey = "pay_1", orderId = order.id, claimedStatus = "CANCELED", plant = "Kim Minsu kim@example.com 010-1234-5678 4111-1111-1111-1111")

        kit.deliver(delivery)

        val row = inbox(kit).single()
        assertEquals("scripted", row.provider)
        assertEquals("evt-ids", row.eventKey)
        assertEquals("PAYMENT_STATUS_CHANGED", row.eventType)
        assertEquals("PAYMENT", row.resourceType)
        assertEquals("pay_1", row.resourceId)
        assertEquals(order.id, row.orderId)
        assertNull(row.payload)
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(delivery.body).joinToString("") { "%02x".format(it) }, row.payloadHash)
        assertEquals(1, row.deliveries)
        assertEquals(kit.time.now(), row.receivedAt)
        listOf("Kim", "kim@example.com", "010-1234", "4111-1111", "CANCELED").forEach { planted ->
            assertFalse(row.toString().contains(planted) || listOf(row.eventKey, row.eventType, row.resourceId, row.orderId, row.payload, row.lastError).any { it?.contains(planted) == true }, "planted $planted")
        }
    }

    // ---- 스펙 §3 8: 중복 · 같은 일을 몇 번 처리해도 ----

    @Test
    fun `processing twice changes nothing`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        kit.runWebhooks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        val afterFirst = kit.order(order.id)
        val eventsAfterFirst = kit.store.listEvents(order.id)
        val tasksAfterFirst = kit.tasks.all().size
        assertEquals(PaymentOrderStatus.PAID, afterFirst.status)

        val rowId = inbox(kit).single().id
        kit.webhook.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, rowId.toString(), 1, 5)) // 같은 작업이 한 번 더 돈다
        val redelivery = kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) // 결제사가 같은 이벤트를 다시 보낸다
        kit.runWebhooks()

        assertEquals(WebhookReceipt.DUPLICATE, redelivery)
        assertEquals(afterFirst, kit.order(order.id))
        assertEquals(eventsAfterFirst, kit.store.listEvents(order.id), "no new history line")
        assertEquals(tasksAfterFirst, kit.tasks.all().size, "no new job - a processed event is not run again")
        assertEquals(1, kit.listener.paid.size)
        assertEquals(1, inbox(kit).size)
        assertEquals(2, inbox(kit).single().deliveries)
    }

    @Test
    fun `a redelivery of an event whose job is still waiting only counts the delivery - sixteen sends, one row, one job`() {
        val kit = PaymentLedgerKit()
        kit.createAuthenticated()
        val order = kit.store.allOrders().single()

        assertEquals(WebhookReceipt.STORED, kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)))
        repeat(15) { assertEquals(WebhookReceipt.DUPLICATE, kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))) }

        assertEquals(1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size, "the job that waits is the only one - a flood of resends costs no more lookups")
        assertEquals(16, inbox(kit).single().deliveries)
        assertEquals(1, inbox(kit).size)

        kit.runWebhooks()

        assertEquals(PaymentWebhookStatus.PROCESSED, inbox(kit).single().status)
        assertEquals(listOf(InMemoryPaymentTasks.State.DONE), kit.tasks.all(PaymentTaskTypes.WEBHOOK).map { it.state }.distinct())
        assertEquals(1, kit.gateway.lookupCalls.size)
    }

    @Test
    fun `resends of a failed event queue one job and put the row back to received - the next resends find a waiting job`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(1)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        kit.runWebhooks()
        assertEquals(PaymentWebhookStatus.FAILED, inbox(kit).single().status)
        val jobsBefore = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

        val receipts = (1..3).map { kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) }

        assertEquals(listOf(WebhookReceipt.REQUEUED, WebhookReceipt.DUPLICATE, WebhookReceipt.DUPLICATE), receipts)
        assertEquals(jobsBefore + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size, "three resends of a failed event add one job, not three")
        assertEquals(PaymentWebhookStatus.RECEIVED, inbox(kit).single().status)
        assertEquals(4, inbox(kit).single().deliveries)
    }

    @Test
    fun `resends of a dead event queue one job - the second resend finds the row waiting`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(5)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        repeat(5) { kit.runWebhooks() }
        assertEquals(PaymentWebhookStatus.DEAD, inbox(kit).single().status)
        val jobsBefore = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

        val receipts = (1..3).map { kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) }

        assertEquals(listOf(WebhookReceipt.REQUEUED, WebhookReceipt.DUPLICATE, WebhookReceipt.DUPLICATE), receipts)
        assertEquals(jobsBefore + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size)
    }

    @Test
    fun `a failing event that is resent over and over stops adding jobs at the redelivery limit - the jobs of a line do not grow without end`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(10_000)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        val receipts = (1..30).map {
            kit.runWebhooks() // the job fails: the row is FAILED while the failed job still lives in its backoff
            kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) // the provider sends it again
        }

        // deliveries 2..10 queue (9 jobs), the first delivery had its job: 10 jobs, then DUPLICATE for the rest
        assertEquals(List(9) { WebhookReceipt.REQUEUED } + List(21) { WebhookReceipt.DUPLICATE }, receipts)
        assertEquals(10, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size)
        assertEquals(31, inbox(kit).single().deliveries)
    }

    @Test
    fun `a dead mode-mismatch line is not turned into a new job by every resend`() {
        val kit = PaymentLedgerKit(PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, redeliveryRequeueLimit = 2)))
        val order = confirmingButPaidAtProvider(kit)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(inbox(kit).single().id)).copy(status = PaymentWebhookStatus.DEAD, lastError = "MODE_MISMATCH")) }

        val receipts = (1..6).map { kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) }
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(inbox(kit).single().id)).copy(status = PaymentWebhookStatus.DEAD)) }
        val after = (1..3).map { kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) }

        assertEquals(listOf(WebhookReceipt.REQUEUED) + List(5) { WebhookReceipt.DUPLICATE }, receipts, "the second delivery is within the limit of 2, the rest are not")
        assertEquals(List(3) { WebhookReceipt.DUPLICATE }, after, "once past the limit a dead line stays dead for resends")
        assertEquals(2, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size)
    }

    @Test
    fun `the redelivery limit counts the total deliveries of the line, the first one included - 1 never queues, 2 queues the second delivery only`() {
        fun resends(limit: Int): Pair<List<WebhookReceipt>, Int> {
            val kit = PaymentLedgerKit(PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, redeliveryRequeueLimit = limit)))
            val order = confirmingButPaidAtProvider(kit)
            kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) // deliveries = 1
            val id = inbox(kit).single().id
            val receipts = (1..3).map {
                kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(id)).copy(status = PaymentWebhookStatus.DEAD)) }
                kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) // deliveries = 2, 3, 4
            }
            return receipts to kit.tasks.all(PaymentTaskTypes.WEBHOOK).size
        }

        assertEquals(List(3) { WebhookReceipt.DUPLICATE } to 1, resends(1), "limit 1: the second delivery is already past it")
        assertEquals(listOf(WebhookReceipt.REQUEUED, WebhookReceipt.DUPLICATE, WebhookReceipt.DUPLICATE) to 2, resends(2), "limit 2: deliveries 2 is within it, 3 and 4 are not")
    }

    @Test
    fun `the operator retry ignores the redelivery limit`() {
        val kit = PaymentLedgerKit(PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN, redeliveryRequeueLimit = 0)))
        val order = confirmingButPaidAtProvider(kit)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        val id = inbox(kit).single().id
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(id)).copy(status = PaymentWebhookStatus.DEAD)) }
        assertEquals(WebhookReceipt.DUPLICATE, kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)), "limit 0: a resend never queues")
        val jobs = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

        kit.inbox.retry(id)

        assertEquals(jobs + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size)
    }

    @Test
    fun `a resend of a finished event - processed or ignored - does nothing but count`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "e-done", orderId = order.id))
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "e-ignored", paymentKey = "pay_ghost", orderId = order.id))
        kit.runWebhooks()
        val before = inbox(kit).associate { it.eventKey to it.status }
        val jobs = kit.tasks.all().size

        listOf("e-done", "e-ignored").forEach { key -> assertEquals(WebhookReceipt.DUPLICATE, kit.deliver(kit.gateway.webhookDelivery(eventKey = key, orderId = order.id))) }

        assertEquals(before, inbox(kit).associate { it.eventKey to it.status })
        assertEquals(jobs, kit.tasks.all().size)
        assertEquals(setOf(2), inbox(kit).map { it.deliveries }.toSet())
    }

    @Test
    fun `different delivery ids of one payment are different rows - the result is the same either way`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)

        kit.deliver(kit.gateway.webhookDelivery(eventKey = "e1", orderId = order.id))
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "e2", orderId = order.id))
        kit.runWebhooks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(2, inbox(kit).size)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(1, kit.listener.paid.size)
        assertEquals(1, kit.store.listEvents(order.id).count { it.toStatus == "PAID" && it.fromStatus != it.toStatus }, "PAID was written once")
    }

    // ---- 스펙 §3 9: 순서 뒤바뀜 ----

    @Test
    fun `a late failure after success keeps the order paid`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()

        // ① 늦게 도착한 "실패" 웹훅 — 결제사의 지금 상태는 승인이다
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "late-1", orderId = order.id, claimedStatus = "ABORTED"))
        kit.runWebhooks()
        // ② 결제사 조회가 실제로 실패를 말하는 이상한 경우에도 PAID 를 되돌리지 않는다
        kit.gateway.abort("pay_1")
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "late-2", orderId = order.id, claimedStatus = "ABORTED"))
        kit.runWebhooks()

        val after = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, after.status)
        assertNull(after.failureCode)
        assertNull(after.closedAt)
        assertEquals(1, kit.listener.paid.size)
        assertEquals(listOf(PaymentWebhookStatus.PROCESSED, PaymentWebhookStatus.PROCESSED), inbox(kit).map { it.status })
    }

    @Test
    fun `a cancel that arrives before the confirm settles ends as refunded`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit) // 승인 응답은 유실, 결제사는 승인했다
        kit.gateway.consoleCancel("pay_1", 3000) // 그리고 결제사 관리 화면에서 전액 취소됐다 — 취소 웹훅이 승인 확정보다 먼저 온다

        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id, claimedStatus = "CANCELED"))
        kit.runWebhooks()
        kit.advance(firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE)) // 늦게 도는 조회 작업은 이미 끝난 줄을 건드리지 않는다

        val after = kit.order(order.id)
        assertEquals(PaymentOrderStatus.REFUNDED, after.status)
        assertEquals(3000, after.refundedAmount)
        assertEquals(PaymentRefundOrigin.PROVIDER, kit.store.listRefunds(order.id).single().origin)
        assertNull(after.reviewReason)
        assertEquals(PaymentWebhookStatus.PROCESSED, inbox(kit).single().status)
    }

    // ---- 스펙 §3 10 · 11: 처리 실패 → 재시도, 재전송은 대기를 건너뛴다 ----

    @Test
    fun `a delivery whose handler failed is retried and then succeeds`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(2)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        kit.runWebhooks()
        val afterFirst = inbox(kit).single()
        kit.runWebhooks()
        val afterSecond = inbox(kit).single()
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status, "nothing settled while the provider could not be asked")
        kit.runWebhooks()

        assertEquals(PaymentWebhookStatus.FAILED, afterFirst.status)
        assertEquals("LOOKUP_UNAVAILABLE", afterFirst.lastError)
        assertEquals(PaymentWebhookStatus.FAILED, afterSecond.status)
        val done = inbox(kit).single()
        assertEquals(PaymentWebhookStatus.PROCESSED, done.status)
        assertNull(done.lastError, "a success clears the earlier failure code")
        assertNotNull(done.processedAt)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(InMemoryPaymentTasks.State.DONE, kit.tasks.all(PaymentTaskTypes.WEBHOOK).single().state)
    }

    @Test
    fun `a redelivery of a dead event runs immediately`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(5) // 큐의 시도 예산(5)을 다 쓴다
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        repeat(5) { kit.runWebhooks() }
        val dead = inbox(kit).single()
        assertEquals(PaymentWebhookStatus.DEAD, dead.status, "the dead-job hook marks the inbox row")
        assertEquals("LOOKUP_UNAVAILABLE", dead.lastError)
        assertEquals(InMemoryPaymentTasks.State.DEAD, kit.tasks.all(PaymentTaskTypes.WEBHOOK).single().state)
        val clock = kit.time.now()

        val receipt = kit.deliver(kit.gateway.webhookDelivery(orderId = order.id)) // 원인을 고치고 결제사가 같은 이벤트를 다시 보냈다 - 시계는 그대로
        val queued = kit.tasks.pending(PaymentTaskTypes.WEBHOOK).single()
        assertEquals(WebhookReceipt.REQUEUED, receipt)
        assertNull(queued.task.runAt, "no wait: the new job is due now")
        assertEquals(PaymentWebhookStatus.RECEIVED, inbox(kit).single().status)
        assertEquals(2, inbox(kit).single().deliveries)
        kit.runWebhooks()

        assertEquals(clock, kit.time.now())
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(PaymentWebhookStatus.PROCESSED, inbox(kit).single().status)
    }

    @Test
    fun `a redelivery of a failed event skips the retry wait too`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(1)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        kit.runWebhooks()
        assertEquals(PaymentWebhookStatus.FAILED, inbox(kit).single().status)
        val jobsBefore = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

        val receipt = kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        assertEquals(WebhookReceipt.REQUEUED, receipt)
        assertEquals(jobsBefore + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size, "one more job, due now")
        assertEquals(PaymentWebhookStatus.RECEIVED, inbox(kit).single().status)
    }

    @Test
    fun `a dead webhook job marks only an unfinished row - the hook is safe to call twice`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "unfinished", orderId = order.id))
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "finished", orderId = order.id))
        val (unfinished, finished) = inbox(kit).sortedBy { it.id }
        kit.webhook.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, finished.id.toString(), 1, 5))

        listOf(unfinished, finished).forEach { kit.runner.onDead(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, it.id.toString(), 5, 5)) }
        kit.runner.onDead(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, unfinished.id.toString(), 5, 5))
        kit.runner.onDead(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, "999999", 5, 5))

        assertEquals(PaymentWebhookStatus.DEAD, kit.store.findWebhookEvent(unfinished.id)!!.status)
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(finished.id)!!.status, "a finished row is left alone")
    }

    // ---- 처리기의 판단 ----

    @Test
    fun `a webhook for a confirming order made under another payment mode is dead at once and flags the order without asking the provider`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.script(dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", order.id, "pay_1", order.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        kit.gateway.mode = PaymentMode.LIVE // 개발 주소에도 등록된 실결제 웹훅 같은 설정 오류

        kit.runWebhooks()

        val row = inbox(kit).single()
        assertEquals(PaymentWebhookStatus.DEAD, row.status)
        assertEquals("MODE_MISMATCH", row.lastError)
        assertEquals(PaymentReviewReason.MODE_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(0, kit.gateway.lookupCalls.size)
        assertEquals(InMemoryPaymentTasks.State.DEAD, kit.tasks.all(PaymentTaskTypes.WEBHOOK).single().state, "no retry for a mode that cannot change")
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).single().attempts)
    }

    @Test
    fun `a mode mismatch marks only a confirming order - a pending or finished order gets no mark, the row alone goes dead`() {
        val markedOnes = listOf(PaymentOrderStatus.CONFIRMING)
        val unmarked = listOf(PaymentOrderStatus.PENDING, PaymentOrderStatus.PAID, PaymentOrderStatus.PARTIALLY_REFUNDED, PaymentOrderStatus.REFUNDED, PaymentOrderStatus.FAILED, PaymentOrderStatus.EXPIRED, PaymentOrderStatus.CANCELED)
        (markedOnes + unmarked).forEach { status ->
            val kit = PaymentLedgerKit()
            val order = kit.createAuthenticated()
            kit.store.inTransaction { tx -> tx.updateOrder(checkNotNull(tx.lockOrder(order.id)).copy(status = status)) }
            kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
            kit.gateway.mode = PaymentMode.LIVE

            kit.runWebhooks()

            val row = inbox(kit).single()
            assertEquals(PaymentWebhookStatus.DEAD, row.status, "from $status")
            assertEquals("MODE_MISMATCH", row.lastError, "from $status")
            assertEquals(if (status in markedOnes) PaymentReviewReason.MODE_MISMATCH else null, kit.order(order.id).reviewReason, "from $status")
            assertEquals(if (status in markedOnes) 1 else 0, kit.review.flagged.size, "from $status")
            assertEquals(0, kit.gateway.lookupCalls.size, "from $status")
        }
    }

    @Test
    fun `a lookup that finds no payment ends ignored with our code`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order // 결제창을 열기만 했다 - 결제사에는 아무것도 없다
        kit.deliver(kit.gateway.webhookDelivery(paymentKey = "pay_ghost", orderId = order.id))

        kit.runWebhooks()

        val row = inbox(kit).single()
        assertEquals(PaymentWebhookStatus.IGNORED, row.status)
        assertEquals("NO_PAYMENT", row.lastError)
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    @Test
    fun `the lookup time is handed to the convergence so a refund that lands meanwhile is no false mismatch`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        // 웹훅 조회가 끝난 뒤 · 주문을 잠그기 전에 운영자의 환불이 확정된다 → 조회 결과(남은 금액 3,000)는 낡았다
        kit.gateway.afterLookup = {
            kit.gateway.afterLookup = null
            kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("k-1"))
        }

        kit.runWebhooks()

        val after = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PARTIALLY_REFUNDED, after.status)
        assertEquals(1000, after.refundedAmount)
        assertNull(after.reviewReason, "a stale snapshot is not read as a mismatch")
        assertEquals(PaymentWebhookStatus.PROCESSED, inbox(kit).single().status)
    }

    @Test
    fun `a webhook never closes a pending refund as not applied - that is the refund settlement's job`() {
        val kit = PaymentLedgerKit()
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutAppliedLater)
        val pending = kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        assertEquals(PaymentRefundStatus.PENDING, pending.status)
        kit.advance(Duration.ofMinutes(30)) // settle-delay 가 한참 지났다
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        kit.runWebhooks()

        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(pending.id)!!.status)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `the task refuses to run inside a ledger transaction because it calls the provider`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        val id = inbox(kit).single().id

        assertFailsWith<IllegalStateException> { kit.store.inTransaction { kit.webhook.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, id.toString(), 1, 5)) } }
        assertEquals(0, kit.gateway.lookupCalls.size)
        assertEquals(emptyList(), kit.gateway.callsInsideTransaction.toList())
    }

    @Test
    fun `a failure reason is our code only - an exception message never reaches a row or a job`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.gateway.failLookups(1) { IllegalStateException("card 4111-1111 of Kim Minsu kim@example.com 010-1234-5678") }
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))

        kit.runWebhooks()

        assertEquals("LOOKUP_UNAVAILABLE", inbox(kit).single().lastError)
        val job = kit.tasks.all(PaymentTaskTypes.WEBHOOK).single()
        listOf("Kim", "kim@example.com", "4111-1111", "010-1234").forEach { planted -> assertFalse(job.lastError.orEmpty().contains(planted), "job error carries $planted") }
    }

    @Test
    fun `a failure while settling rolls the settlement back and is recorded as our processing code`() {
        val kit = PaymentLedgerKit()
        val order = confirmingButPaidAtProvider(kit)
        kit.deliver(kit.gateway.webhookDelivery(orderId = order.id))
        val failingTasks = PaymentTasks { error("planted failure with Kim kim@example.com in it") }
        val convergence = PaymentConvergence(kit.writer, failingTasks, kit.review, LedgerRefundConvergence(kit.writer, failingTasks, kit.review))
        val handler = PaymentWebhookTaskHandler(kit.store, kit.router, convergence, kit.writer)
        val id = inbox(kit).single().id

        val thrown = assertFailsWith<PaymentTaskRetryException> { handler.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, id.toString(), 1, 5)) }

        val row = inbox(kit).single()
        assertEquals(PaymentWebhookStatus.FAILED, row.status)
        assertEquals("PROCESSING_FAILED", row.lastError)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status, "the half-done settlement was rolled back")
        assertFalse(thrown.message.orEmpty().contains("Kim"))
    }

    @Test
    fun `a gateway that hands over identifiers that are not plain is refused before anything is stored`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        val gateway = object : PaymentWebhookGateway by kit.gateway {
            override fun parse(delivery: WebhookDelivery) = WebhookEnvelope(eventKey = "has spaces <b>", eventType = "PAYMENT_STATUS_CHANGED", resourceType = "payment", resourceId = "pay_1", orderId = order.id)
        }

        assertFailsWith<PaymentWebhookRejectedException> { kit.inbox.receive(gateway, kit.gateway.webhookDelivery()) }
        assertEquals(emptyList(), inbox(kit))
    }

    @Test
    fun `the webhook job has a type of its own and the runner knows it`() {
        val kit = PaymentLedgerKit()

        assertEquals("payment-webhook", PaymentTaskTypes.WEBHOOK)
        assertTrue(PaymentTaskTypes.WEBHOOK in kit.runner.types)
    }
}
