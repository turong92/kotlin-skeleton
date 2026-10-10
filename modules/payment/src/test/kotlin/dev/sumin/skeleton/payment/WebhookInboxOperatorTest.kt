package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 운영자의 수신함 입구 — 「다시 처리」(retry)와 목록(상태 필터 + 커서, 최신순). HTTP 는 B7 이 이 서비스 위에 얹는다. */
class WebhookInboxOperatorTest {
    private fun confirmingOrder(kit: PaymentLedgerKit): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutApplied())
        return kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
    }

    private fun delivered(kit: PaymentLedgerKit, order: PaymentOrder, key: String = "evt-1"): PaymentWebhookEvent {
        kit.deliver(kit.gateway.webhookDelivery(eventKey = key, orderId = order.id))
        return kit.store.listWebhookEvents(null, null, 100).first { it.eventKey == key }
    }

    @Test
    fun `retrying an id that does not exist throws the not-found exception`() {
        val kit = PaymentLedgerKit()

        val error = assertFailsWith<PaymentWebhookEventNotFoundException> { kit.inbox.retry(424242) }

        assertEquals(PaymentErrorCode.WEBHOOK_EVENT_NOT_FOUND, error.errorCode)
        assertEquals(404, error.status.value())
    }

    @Test
    fun `retrying a processed row returns it as it is and queues nothing`() {
        val kit = PaymentLedgerKit()
        val order = confirmingOrder(kit)
        val row = delivered(kit, order)
        kit.runWebhooks()
        val processed = kit.store.findWebhookEvent(row.id)!!
        val jobs = kit.tasks.all().size

        val returned = kit.inbox.retry(row.id)

        assertEquals(PaymentWebhookStatus.PROCESSED, processed.status)
        assertEquals(processed, returned)
        assertEquals(processed, kit.store.findWebhookEvent(row.id))
        assertEquals(jobs, kit.tasks.all().size)
    }

    @Test
    fun `retrying a dead, failed or ignored row queues a new job that is due now and returns the row as received`() {
        listOf(PaymentWebhookStatus.DEAD, PaymentWebhookStatus.FAILED, PaymentWebhookStatus.IGNORED).forEach { status ->
            val kit = PaymentLedgerKit()
            val order = confirmingOrder(kit)
            val row = delivered(kit, order)
            kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(row.id)).copy(status = status, lastError = "SOME_CODE".takeIf { status != PaymentWebhookStatus.RECEIVED })) }
            val jobs = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

            val returned = kit.inbox.retry(row.id)

            assertEquals(PaymentWebhookStatus.RECEIVED, returned.status, "from $status")
            assertEquals(returned, kit.store.findWebhookEvent(row.id), "from $status")
            assertEquals(jobs + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size, "from $status")
            assertNull(kit.tasks.all(PaymentTaskTypes.WEBHOOK).last().task.runAt, "from $status: due now")
            assertEquals(row.id.toString(), kit.tasks.all(PaymentTaskTypes.WEBHOOK).last().task.subjectId)
        }
    }

    @Test
    fun `retrying a received row queues a new job too - the operator is a signed-in person, the row may have lost its job`() {
        val kit = PaymentLedgerKit()
        val order = confirmingOrder(kit)
        val row = delivered(kit, order)
        assertEquals(PaymentWebhookStatus.RECEIVED, row.status)
        val jobs = kit.tasks.all(PaymentTaskTypes.WEBHOOK).size

        val returned = kit.inbox.retry(row.id)

        assertEquals(PaymentWebhookStatus.RECEIVED, returned.status)
        assertEquals(jobs + 1, kit.tasks.all(PaymentTaskTypes.WEBHOOK).size)
        assertNull(kit.tasks.all(PaymentTaskTypes.WEBHOOK).last().task.runAt, "due now")
    }

    @Test
    fun `a received row whose job died without marking it is not a dead end - retry puts it through`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated()
        kit.gateway.authenticate("pay_1", order.id, order.amount)
        kit.gateway.settle("pay_1")
        kit.gateway.failLookups(5)
        val row = delivered(kit, order)
        repeat(5) { kit.runWebhooks() }
        // the dead-job hook was lost (the process died between the DEAD commit and the notification): the row is still RECEIVED, the job is DEAD
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(row.id)).copy(status = PaymentWebhookStatus.RECEIVED)) }
        assertEquals(listOf(InMemoryPaymentTasks.State.DEAD), kit.tasks.all(PaymentTaskTypes.WEBHOOK).map { it.state })
        kit.gateway.failLookups(0)

        kit.inbox.retry(row.id)
        kit.runWebhooks()

        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(row.id)!!.status)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `a retried ignored row is looked at again and can now settle the order`() {
        val kit = PaymentLedgerKit()
        val order = kit.create().order // 처음에는 결제사에 결제가 없어 무시됐다
        val row = delivered(kit, order)
        kit.runWebhooks()
        assertEquals(PaymentWebhookStatus.IGNORED, kit.store.findWebhookEvent(row.id)!!.status)
        kit.gateway.authenticate("pay_1", order.id, order.amount)
        kit.gateway.settle("pay_1") // 그 뒤 결제사에서 결제가 끝났다

        kit.inbox.retry(row.id)
        kit.runWebhooks()

        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(PaymentWebhookStatus.PROCESSED, kit.store.findWebhookEvent(row.id)!!.status)
        assertNull(kit.store.findWebhookEvent(row.id)!!.lastError)
    }

    @Test
    fun `the list is newest first, filtered by status, and pages with a cursor that comes back as next`() {
        val kit = PaymentLedgerKit()
        val order = confirmingOrder(kit)
        (1..5).forEach { n ->
            delivered(kit, order, key = "evt-$n")
            kit.advance(Duration.ofSeconds(1))
        }
        val ids = kit.store.listWebhookEvents(null, null, 10).associate { it.eventKey to it.id }
        kit.store.inTransaction { tx -> tx.updateWebhookEvent(checkNotNull(tx.lockWebhookEvent(ids.getValue("evt-2"))).copy(status = PaymentWebhookStatus.DEAD, lastError = "LOOKUP_UNAVAILABLE")) }

        val first = kit.inbox.list(null, null, 2)
        val second = kit.inbox.list(null, first.next, 2)
        val third = kit.inbox.list(null, second.next, 2)

        assertEquals(listOf("evt-5", "evt-4"), first.values.map { it.eventKey })
        assertEquals(listOf("evt-3", "evt-2"), second.values.map { it.eventKey })
        assertEquals(listOf("evt-1"), third.values.map { it.eventKey })
        assertTrue(first.next != null && second.next != null)
        assertNull(third.next, "no more pages")
        assertEquals(listOf("evt-2"), kit.inbox.list(PaymentWebhookStatus.DEAD, null, 10).values.map { it.eventKey })
        assertNull(kit.inbox.list(PaymentWebhookStatus.DEAD, null, 10).next)
        assertEquals(emptyList(), kit.inbox.list(PaymentWebhookStatus.PROCESSED, null, 10).values)
    }

    @Test
    fun `a page size outside one to one hundred is clamped`() {
        val kit = PaymentLedgerKit()
        val order = confirmingOrder(kit)
        (1..3).forEach { delivered(kit, order, key = "evt-$it") }

        assertEquals(1, kit.inbox.list(null, null, 0).values.size)
        assertEquals(1, kit.inbox.list(null, null, -5).values.size)
        assertEquals(3, kit.inbox.list(null, null, 100_000).values.size)
    }
}
