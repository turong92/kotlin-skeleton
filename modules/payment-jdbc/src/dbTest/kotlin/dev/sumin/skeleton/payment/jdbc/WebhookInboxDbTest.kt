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

/** 웹훅 수신함 — 진짜 DB · 진짜 작업 큐(두 방언)에서: 죽은 이벤트의 재전송, 같은 주문의 이벤트 열여섯, 승인 응답 ↔ 웹훅 경주, 죽은 작업과 재전송. (같은 배달·주문 단위 상한은 `MultiInstanceDbTest`.) */
class WebhookInboxDbTest {
    private val clock = TimeProvider.systemUtc()
    private val repository = JdbcJobRepository(JdbcClient.create(PaymentDb.dataSource), TransactionTemplate(PaymentDb.transactionManager), DbTestDatabase.dialect)

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun rows() = PaymentDb.jdbc.queryForList("select * from payment_webhook_events", emptyMap<String, Any>())

    private fun jobs(type: String = PaymentTaskTypes.WEBHOOK) = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to type)

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
}
