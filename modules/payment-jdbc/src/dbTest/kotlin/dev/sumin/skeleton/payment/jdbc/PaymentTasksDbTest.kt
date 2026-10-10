package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JdbcJobRepository
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueProperties
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.PaymentTaskPermanentException
import dev.sumin.skeleton.payment.PaymentTaskRetryException
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskRunner
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate

/** 작업 어댑터(스펙 §4.4 · §6.3 · §16): 작업 넣기가 장부 트랜잭션과 **같은 연결**을 쓰는지, 워커가 처리기를 트랜잭션 밖에서 돌리는지, 영구 실패가 재시도되지 않는지. */
class PaymentTasksDbTest {
    private val store = PaymentDb.newStore()
    private val clock = TimeProvider.systemUtc()
    private val repository = JdbcJobRepository(JdbcClient.create(PaymentDb.dataSource), TransactionTemplate(PaymentDb.transactionManager), DbTestDatabase.dialect)
    private val queue = JdbcJobQueue(repository, JobQueueProperties(), clock)
    private val tasks = JdbcPaymentTasks(queue, store)
    private val runs = CopyOnWriteArrayList<PaymentTaskRun>()
    private val openTransactionDuringRun = CopyOnWriteArrayList<Boolean>()
    private var behavior: (PaymentTaskRun) -> Unit = {}

    private val runner = object : PaymentTaskRunner {
        override val types = setOf("payment-order-expire", "payment-app-extra")
        override fun run(run: PaymentTaskRun) {
            runs += run
            openTransactionDuringRun += store.hasOpenTransaction()
            behavior(run)
        }
    }
    private val handlers: List<JobHandler> = runner.types.map { PaymentJobHandler(it, runner) }
    private val worker = JobQueueWorker(repository, handlers, JobQueueProperties(), clock)

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun jobs(): Long = PaymentDb.count("select count(*) from jobs")

    @Test
    fun `a task enqueued in a committed ledger transaction is a job with the subject, run time and attempt limit`() {
        val runAt = Instant.now().plusSeconds(3600)

        store.inTransaction { tasks.enqueue(PaymentTask("payment-order-expire", "ord_1", runAt = runAt, maxAttempts = 7)) }

        val row = PaymentDb.jdbc.queryForMap("select job_type, payload_json, max_attempts, status from jobs", emptyMap<String, Any>())
        assertEquals("payment-order-expire", row["job_type"])
        assertEquals("""{"subjectId":"ord_1"}""", row["payload_json"])
        assertEquals(7, (row["max_attempts"] as Number).toInt())
        assertEquals("PENDING", row["status"])
        assertEquals(0, worker.pollOnce(), "a job that is not due yet is not taken")
    }

    @Test
    fun `a rollback takes the job row away together with the ledger row - the same connection`() {
        val order = PaymentTestOrders.order(id = "ord_" + "5".repeat(32))

        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx ->
                tx.insertOrderIfAbsent(order)
                tasks.enqueue(PaymentTask("payment-order-expire", order.id))
                assertNotNull(store.findOrder(order.id), "the ledger row is visible inside its own transaction")
                error("boom")
            }
        }

        assertEquals(0, jobs(), "no job survives the rollback")
        assertEquals(0, PaymentDb.count("select count(*) from payment_orders"))
    }


    @Test
    fun `a task enqueued outside a ledger transaction is refused and leaves nothing`() {
        val failure = assertFailsWith<IllegalStateException> { tasks.enqueue(PaymentTask("payment-order-expire", "ord_1")) }

        assertThat(failure.message).contains("transaction")
        assertEquals(0, jobs())
    }

    @Test
    fun `a subject id that is not a plain identifier is refused - the payload is built by hand`() {
        store.inTransaction {
            assertFailsWith<IllegalArgumentException> { tasks.enqueue(PaymentTask("payment-order-expire", "ord\"},{\"x\":\"")) }
            assertFailsWith<IllegalArgumentException> { tasks.enqueue(PaymentTask("payment-order-expire", "")) }
        }
        assertEquals(0, jobs())
    }

    @Test
    fun `the worker hands the handler the subject, the attempt and the limit - outside any transaction`() {
        store.inTransaction { tasks.enqueue(PaymentTask("payment-order-expire", "ord_1", maxAttempts = 4)) }

        assertEquals(1, worker.pollOnce())

        val run = runs.single()
        assertEquals(PaymentTaskRun("payment-order-expire", "ord_1", attempt = 1, maxAttempts = 4), run)
        assertEquals(listOf(false), openTransactionDuringRun.toList(), "the worker does not wrap a payment handler in a transaction")
        assertEquals("DONE", PaymentDb.jdbc.queryForObject("select status from jobs", emptyMap<String, Any>(), String::class.java))
    }


    @Test
    fun `a permanent failure kills the job at once - no retry - and carries only our sentence`() {
        behavior = { throw PaymentTaskPermanentException("mode mismatch for order ord_1") }
        store.inTransaction { tasks.enqueue(PaymentTask("payment-order-expire", "ord_1")) }

        worker.pollOnce()

        val row = PaymentDb.jdbc.queryForMap("select status, attempts, last_error from jobs", emptyMap<String, Any>())
        assertEquals("DEAD", row["status"])
        assertEquals(1, (row["attempts"] as Number).toInt())
        assertThat(row["last_error"].toString()).contains("mode mismatch for order ord_1")
    }

    @Test
    fun `a retry request keeps the job pending with a later run time`() {
        behavior = { throw PaymentTaskRetryException("provider still working on ord_1") }
        store.inTransaction { tasks.enqueue(PaymentTask("payment-order-expire", "ord_1", maxAttempts = 3)) }

        worker.pollOnce()

        val row = PaymentDb.jdbc.queryForMap("select status, attempts from jobs", emptyMap<String, Any>())
        assertEquals("PENDING", row["status"])
        assertEquals(1, (row["attempts"] as Number).toInt())
        assertEquals(0, worker.pollOnce(), "backed off - not due again right away")
    }

    @Test
    fun `an unexpected failure uses the queue's retry and the last attempt ends dead`() {
        behavior = { throw IllegalStateException("planted") }
        store.inTransaction { tasks.enqueue(PaymentTask("payment-order-expire", "ord_1", maxAttempts = 1)) }

        worker.pollOnce()

        assertEquals("DEAD", PaymentDb.jdbc.queryForObject("select status from jobs", emptyMap<String, Any>(), String::class.java))
    }

    @Test
    fun `a job whose payload is not ours is killed as permanent - nothing to retry`() {
        queue.enqueue("payment-order-expire", """{"unexpected":true}""")

        worker.pollOnce()

        assertEquals("DEAD", PaymentDb.jdbc.queryForObject("select status from jobs", emptyMap<String, Any>(), String::class.java))
        assertTrue(runs.isEmpty())
    }

}
