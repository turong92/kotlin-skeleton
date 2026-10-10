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

    @Test
    fun `confirm and refund refuse to run inside an app transaction - the provider is never called and nothing stays`() {
        val paid = kit.paid()
        val pending = kit.createAuthenticated(key = "key-2", paymentKey = "pay_2")

        assertThat(assertFailsWith<IllegalStateException> { outer.execute { kit.orders.confirm("acc_1", pending.id, "pay_2", pending.amount) } }.message).contains("transaction")
        assertThat(assertFailsWith<IllegalStateException> { outer.execute { kit.refunds.refund("op_1", paid.id, "OPERATOR", 1000, IdempotencyKey("k-1")) } }.message).contains("transaction")

        assertEquals(emptyList(), kit.gateway.refundCalls.toList())
        assertEquals(1, kit.gateway.confirmCalls.size, "only the confirm that paid the first order - the money never moved inside the app transaction")
        assertEquals(PaymentOrderStatus.PENDING, kit.order(pending.id).status)
        assertEquals(0, kit.store.listRefunds(paid.id).size)
    }

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
}
