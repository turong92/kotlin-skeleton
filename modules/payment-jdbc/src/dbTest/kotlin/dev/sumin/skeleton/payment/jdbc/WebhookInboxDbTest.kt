package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.payment.DefaultPaymentTaskRunner
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.WebhookReceipt
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/** 웹훅 수신함 — 진짜 DB · 진짜 작업 큐(두 방언)에서: 같은 전달의 중복 수신, 승인 응답 ↔ 웹훅 경주, 죽은 작업과 재전송. */
class WebhookInboxDbTest {
    private val clock = TimeProvider.systemUtc()
    private val repository = JdbcJobRepository(JdbcClient.create(PaymentDb.dataSource), TransactionTemplate(PaymentDb.transactionManager), DbTestDatabase.dialect)

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun rows() = PaymentDb.jdbc.queryForList("select * from payment_webhook_events", emptyMap<String, Any>())

    private fun jobs(type: String = PaymentTaskTypes.WEBHOOK) = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to type)

    @Test
    fun `the same delivery sixteen times stores one row`() {
        val kit = JdbcLedgerKit()
        val order = kit.createAuthenticated()
        val delivery = kit.gateway.webhookDelivery(eventKey = "evt-sixteen", orderId = order.id)

        val results = race(16) { kit.deliver(delivery) }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        val receipts = results.map { it.getOrThrow() }
        assertEquals(1, receipts.count { it == WebhookReceipt.STORED }, "exactly one delivery made the row")
        assertEquals(15, receipts.count { it == WebhookReceipt.DUPLICATE }, "the other fifteen found a row whose job is already waiting")
        assertEquals(1, rows().size)
        assertEquals(16, kit.store.listWebhookEvents(null, null, 10).single().deliveries)
        assertEquals(1, jobs(), "sixteen deliveries at once, one job")
    }

    @Test
    fun `sixteen resends of a dead event queue exactly one job - the first one puts the row back to received`() {
        val kit = JdbcLedgerKit()
        val order = kit.createAuthenticated()
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-dead-16", orderId = order.id))
        val id = kit.store.listWebhookEvents(null, null, 1).single().id
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(id)).copy(status = PaymentWebhookStatus.DEAD, lastError = "LOOKUP_UNAVAILABLE")) }
        val jobsBefore = jobs()

        val results = race(16) { kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-dead-16", orderId = order.id)) }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        val receipts = results.map { it.getOrThrow() }
        assertEquals(1, receipts.count { it == WebhookReceipt.REQUEUED })
        assertEquals(15, receipts.count { it == WebhookReceipt.DUPLICATE })
        assertEquals(jobsBefore + 1, jobs())
        assertEquals(PaymentWebhookStatus.RECEIVED, kit.store.findWebhookEvent(id)!!.status)
    }

    @Test
    fun `three resends of a failed event in a row are one job`() {
        val kit = JdbcLedgerKit()
        val order = kit.createAuthenticated()
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-failed-3", orderId = order.id))
        val id = kit.store.listWebhookEvents(null, null, 1).single().id
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(id)).copy(status = PaymentWebhookStatus.FAILED, lastError = "LOOKUP_UNAVAILABLE")) }
        val jobsBefore = jobs()

        val receipts = (1..3).map { kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-failed-3", orderId = order.id)) }

        assertEquals(listOf(WebhookReceipt.REQUEUED, WebhookReceipt.DUPLICATE, WebhookReceipt.DUPLICATE), receipts)
        assertEquals(jobsBefore + 1, jobs())
    }

    @Test
    fun `sixteen operator retries of a dead row each queue a job - the operator is not throttled`() {
        val kit = JdbcLedgerKit()
        val order = kit.createAuthenticated()
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-retry-16", orderId = order.id))
        val id = kit.store.listWebhookEvents(null, null, 1).single().id
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(id)).copy(status = PaymentWebhookStatus.DEAD, lastError = "LOOKUP_UNAVAILABLE")) }
        val jobsBefore = jobs()

        val results = race(16) { kit.inbox.retry(id) }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(jobsBefore + 16, jobs(), "a retry of a RECEIVED row queues a job too (the row may have lost its job)")
        assertEquals(PaymentWebhookStatus.RECEIVED, kit.store.findWebhookEvent(id)!!.status)
    }

    @Test
    fun `the per-order limit counts rows in the table - two instances share one count and the thirty-first new event is not stored`() {
        val a = JdbcLedgerKit()
        val b = JdbcLedgerKit()
        val order = a.createAuthenticated()
        a.gateway.authenticate("pay_1", order.id, order.amount)
        val receipts = (1..30).map { n -> (if (n % 2 == 0) a else b).deliver(a.gateway.webhookDelivery(eventKey = "evt-lim-$n", paymentKey = "pay_forged_$n", orderId = order.id)) }
        val jobsBefore = jobs()

        val limited = listOf(a, b).map { it.deliver(a.gateway.webhookDelivery(eventKey = "evt-lim-31", paymentKey = "pay_x", orderId = order.id)) }
        val again = b.deliver(a.gateway.webhookDelivery(eventKey = "evt-lim-4", orderId = order.id))

        assertEquals(List(30) { WebhookReceipt.STORED }, receipts)
        assertEquals(listOf(WebhookReceipt.LIMITED, WebhookReceipt.LIMITED), limited)
        assertEquals(WebhookReceipt.DUPLICATE, again, "a resend of a stored event is no new event")
        val lines = a.store.listWebhookEvents(null, null, 100)
        assertEquals(30, lines.count { it.resourceType == "PAYMENT" })
        assertEquals(1, lines.count { it.resourceType == "LIMIT_MARKER" }, "the refused ones left one marker row for the window")
        assertEquals(2, lines.single { it.resourceType == "LIMIT_MARKER" }.deliveries)
        assertEquals(jobsBefore + 1, jobs(), "no job for the refused ones but the one deferred job of the marker")
        assertEquals(setOf("order:${order.id}"), lines.map { it.resourceId }.toSet())
    }

    @Test
    fun `sixteen different events of one order leave sixteen rows and the order settles once`() {
        val kit = JdbcLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        (1..16).forEach { kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-$it", orderId = created.id)) }

        val results = race(16) { n ->
            val id = kit.store.listWebhookEvents(null, null, 100).first { it.eventKey == "evt-${n + 1}" }.id
            kit.webhook.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, id.toString(), 1, 5))
        }

        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(PaymentOrderStatus.PAID, kit.order(created.id).status)
        assertEquals(1, kit.store.listEvents(created.id).count { it.toStatus == "PAID" && it.fromStatus != it.toStatus }, "PAID was written once")
        assertEquals(1, jobs(PaymentTaskTypes.ORDER_FULFIL), "one fulfil job")
        assertEquals(setOf(PaymentWebhookStatus.PROCESSED), kit.store.listWebhookEvents(null, null, 100).map { it.status }.toSet())
    }

    @Test
    fun `an approval response and a webhook racing leave no deadlock and one winner`() {
        repeat(12) { round ->
            val kit = JdbcLedgerKit()
            val order = kit.createAuthenticated(key = "k$round", paymentKey = "pay_$round")
            kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-race-$round", paymentKey = "pay_$round", orderId = order.id))
            val eventId = kit.store.listWebhookEvents(null, null, 1).single { it.eventKey == "evt-race-$round" }.id

            val results = race<Any>(2) { n ->
                if (n == 0) kit.orders.confirm("acc_1", order.id, "pay_$round", order.amount)
                else kit.webhook.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, eventId.toString(), 1, 5))
            }

            assertTrue(results.all { it.isSuccess }, "round $round: ${results.mapNotNull { it.exceptionOrNull() }}")
            val final = kit.order(order.id)
            assertEquals(PaymentOrderStatus.PAID, final.status, "round $round")
            assertEquals(1, kit.gateway.confirmCalls.size, "round $round: the provider was asked to confirm once")
            assertEquals(1, kit.store.listEvents(order.id).count { it.toStatus == "PAID" && it.fromStatus != it.toStatus }, "round $round: PAID was written once")
            assertEquals(1, jobs(PaymentTaskTypes.ORDER_FULFIL), "round $round: one fulfil job")
            assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(eventId)!!.status, "round $round")
            PaymentDb.clean()
        }
    }

    @Test
    fun `a webhook job that died stale marks the inbox row dead through the real queue and a resend runs on the next poll`() {
        val kit = JdbcLedgerKit()
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutNotApplied) // 결제사는 아직 진행 중이다 - 조회 작업이 돌아도 맞출 것이 없다
        kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-dead", orderId = created.id))
        val runner = DefaultPaymentTaskRunner(listOf(kit.expire, kit.reconcile, kit.fulfil, kit.refundReconcile, kit.refundApply, kit.webhook))
        val handlers: List<JobHandler> = runner.types.map { PaymentJobHandler(it, runner) }
        val worker = JobQueueWorker(repository, handlers, JobQueueProperties(), clock, deadListeners = listOf(PaymentJobDeadListener { runner }))
        val job = PaymentDb.count("select max(id) from jobs where job_type = :t", "t" to PaymentTaskTypes.WEBHOOK)
        PaymentDb.jdbc.update(
            "update jobs set status = 'RUNNING', attempts = max_attempts, locked_by = 'dead-worker', locked_at = :at where id = :id",
            mapOf("id" to job, "at" to DbTestDatabase.dialect.instantParam(Instant.now().minusSeconds(3600))),
        )

        worker.pollOnce() // 멈춤 복구가 처리기를 거치지 않고 DEAD 로 만든다 — 죽은 작업의 고리가 줄을 DEAD 로

        val dead = kit.store.listWebhookEvents(null, null, 10).single()
        assertEquals(PaymentWebhookStatus.DEAD, dead.status)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(created.id).status, "nothing was settled")

        kit.gateway.settle("pay_1") // 원인이 풀렸다 - 결제사는 결제를 끝냈다
        val receipt = kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-dead", orderId = created.id)) // 결제사가 같은 이벤트를 다시 보냈다
        assertEquals(WebhookReceipt.REQUEUED, receipt)
        assertEquals(PaymentWebhookStatus.RECEIVED, kit.store.findWebhookEvent(dead.id)!!.status)
        worker.pollOnce() // 새 작업은 대기 없이 바로 돈다

        assertEquals(PaymentOrderStatus.PAID, kit.order(created.id).status)
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(dead.id)!!.status)
        assertEquals(2, kit.store.findWebhookEvent(dead.id)!!.deliveries)
    }

    @Test
    fun `retrying a processed row queues nothing and retrying a dead one queues a job that is due now`() {
        val kit = JdbcLedgerKit()
        val created = kit.createAuthenticated()
        kit.deliver(kit.gateway.webhookDelivery(eventKey = "evt-op", orderId = created.id))
        val id = kit.store.listWebhookEvents(null, null, 1).single().id
        kit.webhook.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, id.toString(), 1, 5))
        val jobsBefore = jobs()

        val same = kit.inbox.retry(id)
        assertEquals(PaymentWebhookStatus.PROCESSED, same.status)
        assertEquals(jobsBefore, jobs())

        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(id)).copy(status = PaymentWebhookStatus.DEAD, lastError = "LOOKUP_UNAVAILABLE", processedAt = null)) }
        val queued = kit.inbox.retry(id)

        assertEquals(PaymentWebhookStatus.RECEIVED, queued.status)
        assertEquals(queued, kit.store.findWebhookEvent(id))
        assertEquals(jobsBefore + 1, jobs())
        val dueNow = PaymentDb.count("select count(*) from jobs where job_type = :t and status = 'PENDING' and next_run_at <= :now", "t" to PaymentTaskTypes.WEBHOOK, "now" to DbTestDatabase.dialect.instantParam(Instant.now().plusSeconds(2)))
        assertTrue(dueNow >= 1, "the new job is due now")
    }
}
