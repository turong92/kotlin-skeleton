package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.payment.DefaultPaymentTaskRunner
import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.OrderReconcileTaskHandler
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskRunner
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import dev.sumin.skeleton.payment.testing.ScriptedGateway.RefundScript
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.assertj.core.api.Assertions.assertThat
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/**
 * 죽은 작업의 고리(스펙 §6.3 · §15, B4 리뷰 I-1) — **진짜 큐 · 진짜 DB** 에서 작업이 DEAD 가 되는 두 길이 모두 줄에 표시를 남기는지 본다:
 * ① 워커가 죽어 멈춘 채 시도를 다 쓴 작업(`recoverStale` — 처리기가 마지막 시도를 돌지 못한다) ② 마지막 시도에서 처리기가 DB 오류로 던진 작업(처리기 안의 마지막 시도 처리에 닿지 못한다).
 * 둘 다 처리기 안의 표시는 없었다 — 큐가 부르는 [PaymentJobDeadListener] 만이 메운다.
 */
class PaymentDeadJobDbTest {
    private val kit = JdbcLedgerKit()
    private val clock = TimeProvider.systemUtc()
    private val repository = JdbcJobRepository(JdbcClient.create(PaymentDb.dataSource), TransactionTemplate(PaymentDb.transactionManager), DbTestDatabase.dialect)

    /** `findOrder` 만 깨뜨릴 수 있는 저장소 — "마지막 시도에서 DB 오류로 던진다"를 만든다. 락 · 쓰기는 진짜다. */
    private class FlakyStore(private val real: PaymentLedgerStore) : PaymentLedgerStore by real {
        @Volatile var broken = false

        override fun findOrder(id: String): PaymentOrder? {
            if (broken) throw DataAccessResourceFailureException("planted database failure")
            return real.findOrder(id)
        }
    }

    private val flaky = FlakyStore(kit.store)
    private val runner: PaymentTaskRunner = DefaultPaymentTaskRunner(
        listOf(kit.expire, OrderReconcileTaskHandler(flaky, kit.router, kit.convergence), kit.fulfil, kit.refundReconcile, kit.refundApply),
    )
    private val deadSeen = CopyOnWriteArrayList<Pair<Job, String>>()
    private val recorder = JobDeadListener { job, reason -> deadSeen += job to reason }
    private val listener = PaymentJobDeadListener { runner }
    private val handlers: List<JobHandler> = runner.types.map { PaymentJobHandler(it, runner) }
    private val worker = JobQueueWorker(repository, handlers, JobQueueProperties(), clock, deadListeners = listOf(listener, recorder))

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun confirming(): PaymentOrder {
        val created = kit.createAuthenticated()
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        val order = kit.orders.confirm("acc_1", created.id, "pay_1", created.amount)
        assertEquals(PaymentOrderStatus.CONFIRMING, order.status)
        return order
    }

    private fun jobId(type: String): Long = PaymentDb.count("select max(id) from jobs where job_type = :t", "t" to type)

    private fun status(id: Long): String = PaymentDb.jdbc.queryForObject("select status from jobs where id = :id", mapOf("id" to id), String::class.java)!!

    /** 워커가 작업을 잡은 채 죽었다 — 시도는 다 쓰였고 임대는 한참 전이다. 다음 폴링의 멈춤 복구가 처리기 없이 DEAD 로 만든다. */
    private fun abandonedByDeadWorker(id: Long) {
        PaymentDb.jdbc.update(
            "update jobs set status = 'RUNNING', attempts = max_attempts, locked_by = 'dead-worker', locked_at = :at where id = :id",
            mapOf("id" to id, "at" to DbTestDatabase.dialect.instantParam(Instant.now().minusSeconds(3600))),
        )
    }

    private fun flaggedEvents(orderId: String): Int = kit.store.listEvents(orderId).count { it.type == dev.sumin.skeleton.payment.PaymentEventType.REVIEW_FLAGGED }

    // ---- 조회 작업 ----

    @Test
    fun `a reconcile job that died stale RUNNING - no handler ran - marks the confirming order`() {
        val order = confirming()
        val job = jobId(PaymentTaskTypes.ORDER_RECONCILE)
        assertNull(kit.order(order.id).reviewReason, "nothing marked it before the job died")
        abandonedByDeadWorker(job)

        worker.pollOnce()

        assertEquals("DEAD", status(job))
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason)
        assertEquals(PaymentOrderStatus.CONFIRMING, kit.order(order.id).status, "marked, not decided")
        assertEquals(listOf(order.id to PaymentReviewReason.CONFIRM_UNRESOLVED), kit.review.flagged.toList())
        assertThat(deadSeen.map { it.first.type }).`as`("the other listeners still hear about it").contains(PaymentTaskTypes.ORDER_RECONCILE)
    }

    @Test
    fun `a reconcile job whose last attempt failed on a database error inside the handler marks the order`() {
        val order = confirming()
        val job = jobId(PaymentTaskTypes.ORDER_RECONCILE)
        PaymentDb.jdbc.update(
            "update jobs set max_attempts = 1, next_run_at = :at where id = :id",
            mapOf("id" to job, "at" to DbTestDatabase.dialect.instantParam(Instant.now().minusSeconds(60))),
        )
        flaky.broken = true

        worker.pollOnce()

        assertEquals("DEAD", status(job))
        assertThat(PaymentDb.jdbc.queryForObject("select last_error from jobs where id = :id", mapOf("id" to job), String::class.java)).contains("DataAccessResourceFailureException")
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason, "the handler never reached its own last-attempt marking")
        assertEquals(1, flaggedEvents(order.id))
    }


    @Test
    fun `a reconcile job that died for an order that finished by another way marks nothing`() {
        val paid = kit.paid()
        kit.store.inTransaction { kit.tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_RECONCILE, paid.id, maxAttempts = 3)) }
        abandonedByDeadWorker(jobId(PaymentTaskTypes.ORDER_RECONCILE))

        worker.pollOnce()

        assertNull(kit.order(paid.id).reviewReason)
        assertEquals(0, flaggedEvents(paid.id))
        assertEquals(emptyList(), kit.review.flagged.toList())
    }

    // ---- 환불 정산 작업 ----

    @Test
    fun `a refund settle job that died with the refund still pending marks the order REFUND_MISMATCH`() {
        val order = kit.paid()
        kit.gateway.scriptRefund(RefundScript.TimeoutApplied)
        val refund = kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        assertEquals(PaymentRefundStatus.PENDING, refund.status)
        val job = jobId(PaymentTaskTypes.REFUND_RECONCILE)
        abandonedByDeadWorker(job)

        worker.pollOnce()

        assertEquals("DEAD", status(job))
        assertEquals(PaymentReviewReason.REFUND_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(PaymentRefundStatus.PENDING, kit.store.findRefund(refund.id)!!.status, "the refund line is left for a person")
        assertEquals(1, flaggedEvents(order.id))
    }


    // ---- 지급 · 회수 작업 (B4 리뷰 2회차 I-A) ----

    @Test
    fun `a fulfil job that died marks the unfulfilled paid order and a sync requeues the job and clears the mark`() {
        kit.listener.failPaid(1000)
        val order = kit.paid()
        val job = jobId(PaymentTaskTypes.ORDER_FULFIL)
        abandonedByDeadWorker(job)

        worker.pollOnce()

        assertEquals("DEAD", status(job))
        assertEquals(PaymentReviewReason.FULFILMENT_UNRESOLVED, kit.order(order.id).reviewReason)
        assertNull(kit.order(order.id).fulfilledAt)
        assertEquals(1, flaggedEvents(order.id))

        val jobsBefore = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.ORDER_FULFIL)
        kit.refunds.sync("op_1", order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(jobsBefore + 1, PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.ORDER_FULFIL), "one new job")
    }

    @Test
    fun `a refund apply job that died marks the order and a sync requeues the job and clears the mark`() {
        val order = kit.paid()
        kit.listener.failRefunded(1000)
        val refund = kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("k-1")).refund
        assertEquals(PaymentRefundStatus.SUCCEEDED, refund.status)
        assertNull(kit.store.findRefund(refund.id)!!.appliedAt)
        val job = jobId(PaymentTaskTypes.REFUND_APPLY)
        abandonedByDeadWorker(job)

        worker.pollOnce()

        assertEquals("DEAD", status(job))
        assertEquals(PaymentReviewReason.REFUND_APPLY_UNRESOLVED, kit.order(order.id).reviewReason)
        assertEquals(1, flaggedEvents(order.id))

        val jobsBefore = PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.REFUND_APPLY)
        kit.refunds.sync("op_1", order.id)

        assertNull(kit.order(order.id).reviewReason)
        assertEquals(jobsBefore + 1, PaymentDb.count("select count(*) from jobs where job_type = :t", "t" to PaymentTaskTypes.REFUND_APPLY), "one new job")
    }

    // ---- 고리의 경계 ----


    @Test
    fun `a hook that fails does not stop the queue - the next listener still hears and the worker goes on`() {
        val order = confirming()
        val job = jobId(PaymentTaskTypes.ORDER_RECONCILE)
        abandonedByDeadWorker(job)
        val broken = PaymentJobDeadListener {
            object : PaymentTaskRunner {
                override val types = setOf(PaymentTaskTypes.ORDER_RECONCILE)
                override fun run(run: dev.sumin.skeleton.payment.PaymentTaskRun) = Unit
                override fun onDead(run: dev.sumin.skeleton.payment.PaymentTaskRun) = throw DataAccessResourceFailureException("planted")
            }
        }
        val worker = JobQueueWorker(repository, handlers, JobQueueProperties(), clock, deadListeners = listOf(broken, recorder))

        worker.pollOnce()

        assertEquals("DEAD", status(job))
        assertThat(deadSeen.map { it.first.type }).contains(PaymentTaskTypes.ORDER_RECONCILE)
        assertNull(kit.order(order.id).reviewReason, "the failed hook marked nothing - the queue itself was not harmed")
    }
}
