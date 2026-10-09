package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.IdempotencyKey
import dev.sumin.skeleton.payment.PaymentActor
import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentTask
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.springframework.dao.DuplicateKeyException
import org.springframework.transaction.support.TransactionTemplate

/**
 * **앱이 연 바깥 트랜잭션**(`@Transactional` 등) 안에서의 저장소 — 계약 시험은 `store.inTransaction` 만 열어서 이 길을 한 번도 지나지 않는다.
 * ① 바깥 트랜잭션 안은 "열려 있다"이고 결제사를 부르는 서비스(승인 · 환불)가 던진다 ② 바깥이 롤백되면 줄과 작업 줄이 함께 사라진다
 * ③ "한 트랜잭션 한 주문"의 범위는 **가장 바깥 `inTransaction` 블록**이다 — 물리 트랜잭션이 아니다(블록을 두 번 열면 주문 둘을 잠글 수 있고, 락은 바깥이 끝날 때까지 간다).
 */
class JdbcOuterTransactionDbTest {
    private val kit = JdbcLedgerKit()
    private val store = kit.store
    private val outer = TransactionTemplate(PaymentDb.transactionManager)

    @BeforeTest
    fun clean() = PaymentDb.clean()

    private fun jobs(): Long = PaymentDb.count("select count(*) from jobs")

    private fun orderRows(): Long = PaymentDb.count("select count(*) from payment_orders")

    private fun orderOf(n: Int) = PaymentTestOrders.order(id = "ord_" + n.toString().repeat(32), accountId = "acc_$n")

    // ---- ① 바깥 트랜잭션은 "열려 있다" ----

    @Test
    fun `inside an app transaction the store reports an open transaction - and outside it does not`() {
        assertFalse(store.hasOpenTransaction())

        outer.execute { assertTrue(store.hasOpenTransaction(), "no inTransaction block is open here - the app's transaction alone makes it true") }

        assertFalse(store.hasOpenTransaction())
    }

    @Test
    fun `confirm refuses to run inside an app transaction - the provider is never called and nothing stays`() {
        val order = kit.createAuthenticated()

        val failure = assertFailsWith<IllegalStateException> {
            outer.execute { kit.orders.confirm("acc_1", order.id, "pay_1", order.amount) }
        }

        assertThat(failure.message).contains("transaction")
        assertEquals(emptyList(), kit.gateway.confirmCalls.toList(), "the money never moved")
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
    }

    @Test
    fun `a refund refuses to run inside an app transaction - the provider is never called and the order is untouched`() {
        val paid = kit.paid()

        val failure = assertFailsWith<IllegalStateException> {
            outer.execute { kit.refunds.refund("op_1", paid.id, "OPERATOR", 1000, IdempotencyKey("k-1")) }
        }

        assertThat(failure.message).contains("transaction")
        assertEquals(emptyList(), kit.gateway.refundCalls.toList())
        assertEquals(0, kit.store.listRefunds(paid.id).size)
        assertEquals(0, kit.order(paid.id).refundedAmount)
    }

    @Test
    fun `enqueuing a task is allowed inside an app transaction - it joins it and follows its fate`() {
        outer.execute { tasks("ord_x") }

        assertEquals(1, jobs(), "the app's commit keeps the job")
    }

    private fun tasks(subject: String) = kit.tasks.enqueue(PaymentTask("payment-order-expire", subject))

    // ---- ② 바깥이 롤백되면 함께 사라진다 ----

    @Test
    fun `an app rollback takes the order row, its event and its job away together - the row was visible until then`() {
        val order = orderOf(1)

        outer.execute { status ->
            store.inTransaction { tx ->
                tx.insertOrderIfAbsent(order)
                tx.appendEvent(kit.writer.event(order.id, PaymentEventType.ORDER_CREATED, PaymentActor.USER, "acc_1", to = order.status))
                kit.tasks.enqueue(PaymentTask("payment-order-expire", order.id))
            }
            assertNotNull(store.findOrder(order.id), "the block ended but the app's transaction is still open - the row is there on its connection")
            status.setRollbackOnly()
        }

        assertEquals(0, orderRows())
        assertEquals(0, jobs())
        assertEquals(0, PaymentDb.count("select count(*) from payment_events"))
        assertNull(store.findOrder(order.id))
    }

    @Test
    fun `an app commit keeps the order row and the job together`() {
        val order = orderOf(1)

        outer.execute {
            store.inTransaction { tx ->
                tx.insertOrderIfAbsent(order)
                kit.tasks.enqueue(PaymentTask("payment-order-expire", order.id))
            }
        }

        assertEquals(1, orderRows())
        assertEquals(1, jobs())
    }

    @Test
    fun `a block that throws inside an app transaction marks the whole app transaction for rollback`() {
        val order = orderOf(1)

        assertFailsWith<IllegalStateException> {
            outer.execute {
                store.inTransaction { tx ->
                    tx.insertOrderIfAbsent(order)
                    error("boom")
                }
            }
        }

        assertEquals(0, orderRows())
    }

    // ---- ③ 한-주문 규칙의 범위 = 가장 바깥 inTransaction 블록 ----

    @Test
    fun `inside one block a second order is refused - nested blocks share that block's one order`() {
        store.inTransaction { it.insertOrderIfAbsent(orderOf(1)) }
        store.inTransaction { it.insertOrderIfAbsent(orderOf(2)) }

        assertFailsWith<IllegalStateException> {
            outer.execute {
                store.inTransaction { tx ->
                    tx.lockOrder(orderOf(1).id)
                    store.inTransaction { inner -> inner.lockOrder(orderOf(2).id) }
                }
            }
        }
    }

    @Test
    fun `inside an app transaction each block may take its own order - the rule is per block, not per physical transaction`() {
        store.inTransaction { it.insertOrderIfAbsent(orderOf(1)) }
        store.inTransaction { it.insertOrderIfAbsent(orderOf(2)) }

        val locked = outer.execute {
            val a = store.inTransaction { it.lockOrder(orderOf(1).id) }
            val b = store.inTransaction { it.lockOrder(orderOf(2).id) }
            listOf(a?.id, b?.id)
        }

        assertEquals(listOf(orderOf(1).id, orderOf(2).id), locked, "an account-wide pass over several orders inside the caller's transaction is possible")
    }

    @Test
    fun `a lock taken in a block lasts until the app transaction ends - not until the block ends`() {
        store.inTransaction { it.insertOrderIfAbsent(orderOf(1)) }
        val probe = Executors.newSingleThreadExecutor()
        try {
            outer.execute {
                store.inTransaction { it.lockOrder(orderOf(1).id) }

                assertTrue(lockIsHeldElsewhere(probe, orderOf(1).id), "the block is over but another connection still cannot take the row")
            }
            assertFalse(lockIsHeldElsewhere(probe, orderOf(1).id), "after the app's commit the row is free")
        } finally {
            probe.shutdownNow()
        }
    }

    /** 다른 스레드의 다른 연결로 `for update nowait` — 락이 잡혀 있으면 true. (같은 스레드에서는 바깥 트랜잭션에 합류해 버려서 못 잰다.) */
    private fun lockIsHeldElsewhere(pool: java.util.concurrent.ExecutorService, orderId: String): Boolean =
        pool.submit<Boolean> {
            try {
                TransactionTemplate(PaymentDb.transactionManager).execute {
                    PaymentDb.jdbc.queryForList("select id from payment_orders where id = :id for update nowait", mapOf("id" to orderId), String::class.java)
                }
                false
            } catch (e: org.springframework.dao.DataAccessException) {
                true
            }
        }.get(30, TimeUnit.SECONDS)

    // ---- 같이 고친 것: PostgreSQL 의 넣기는 dedupe 충돌만 삼킨다 ----

    @Test
    fun `an insert that collides on the provider payment id is not reported as a dedupe hit - PostgreSQL raises it`() {
        if (DbTestDatabase.vendor != "postgresql") return // MySQL 의 insert ignore 는 충돌 종류를 가르지 못한다 - 새 주문은 결제 키 없이 들어가므로 실제로는 닿지 않는다
        store.inTransaction { it.insertOrderIfAbsent(orderOf(1).copy(providerPaymentId = "pay_same")) }

        assertFailsWith<DuplicateKeyException> {
            store.inTransaction { it.insertOrderIfAbsent(orderOf(2).copy(providerPaymentId = "pay_same")) }
        }
    }
}
