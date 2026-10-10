package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.payment.OperatorOrderFilter
import dev.sumin.skeleton.payment.OperatorOrderScope
import dev.sumin.skeleton.payment.OrderCursor
import dev.sumin.skeleton.payment.PaymentActor
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentEvent
import dev.sumin.skeleton.payment.PaymentEventType
import dev.sumin.skeleton.payment.PaymentLedgerConflictException
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentLedgerTx
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrder
import dev.sumin.skeleton.payment.PaymentOrderKind
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentOriginType
import dev.sumin.skeleton.payment.PaymentRefund
import dev.sumin.skeleton.payment.PaymentRefundOrigin
import dev.sumin.skeleton.payment.PaymentRefundStatus
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.SubscriptionEndReason
import dev.sumin.skeleton.payment.SubscriptionStatus
import dev.sumin.skeleton.payment.PaymentWebhookEvent
import dev.sumin.skeleton.payment.RefundCursor
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.WebhookEventCursor
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PaymentLedgerStore] 가 지킬 약속. 메모리 대역과 `payment-jdbc` 의 진짜 구현이 **같은 시험**을 통과해야 한다 —
 * 대역이 진짜보다 너그러우면 서비스 시험이 거짓말을 한다. 구현마다 [newStore] 만 채워 상속한다.
 */
abstract class PaymentLedgerStoreContract {
    protected abstract fun newStore(): PaymentLedgerStore

    private val first = PaymentTestOrders.order(id = "ord_" + "1".repeat(32), dedupeKey = "dedupe-1")

    @Test
    fun `an inserted order is readable by id and by dedupe key after commit`() {
        val store = newStore()

        assertTrue(store.inTransaction { it.insertOrderIfAbsent(first) })

        assertEquals(first, store.findOrder(first.id))
        assertEquals(first, store.findOrderByDedupeKey("dedupe-1"))
        assertNull(store.findOrder("ord_missing"))
    }

    @Test
    fun `an insert with a dedupe key that exists is refused and the first row stays`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }

        val second = first.copy(id = "ord_" + "2".repeat(32), orderName = "other")
        val inserted = store.inTransaction { it.insertOrderIfAbsent(second) }

        assertFalse(inserted)
        assertEquals(first, store.findOrderByDedupeKey("dedupe-1"))
        assertNull(store.findOrder(second.id))
    }

    @Test
    fun `orders without a dedupe key never collide`() {
        val store = newStore()

        assertTrue(store.inTransaction { it.insertOrderIfAbsent(first.copy(dedupeKey = null)) })
        assertTrue(store.inTransaction { it.insertOrderIfAbsent(first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)) })
    }

    @Test
    fun `a transaction that throws leaves no order and no event`() {
        val store = newStore()

        assertFailsWith<IllegalArgumentException> {
            store.inTransaction { tx ->
                tx.insertOrderIfAbsent(first)
                tx.appendEvent(event(first.id))
                throw IllegalArgumentException("boom")
            }
        }

        assertNull(store.findOrder(first.id))
        assertEquals(emptyList(), store.listEvents(first.id))
        assertTrue(store.inTransaction { it.insertOrderIfAbsent(first) }, "the dedupe key was released with the rollback")
    }

    @Test
    fun `a checked exception thrown inside the transaction comes out as itself - not wrapped - and rolls back`() {
        val store = newStore()

        val failure = assertFailsWith<java.io.IOException> {
            store.inTransaction { tx ->
                tx.insertOrderIfAbsent(first)
                throw java.io.IOException("checked boom")
            }
        }

        assertEquals("checked boom", failure.message)
        assertNull(store.findOrder(first.id))
    }

    @Test
    fun `an order can be read inside the transaction that inserted it before the commit`() {
        val store = newStore()

        store.inTransaction { tx ->
            tx.insertOrderIfAbsent(first)
            assertEquals(first, tx.findOrderByDedupeKey("dedupe-1"))
            assertEquals(first, tx.lockOrder(first.id))
        }
    }

    @Test
    fun `updating an order that was not locked in this transaction fails`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }

        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx -> tx.updateOrder(first.copy(status = PaymentOrderStatus.PAID)) }
        }
        assertEquals(PaymentOrderStatus.PENDING, store.findOrder(first.id)!!.status)
    }

    @Test
    fun `an update made under the lock is visible after commit and gone after rollback`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }

        store.inTransaction { tx ->
            val locked = assertNotNull(tx.lockOrder(first.id))
            tx.updateOrder(locked.copy(status = PaymentOrderStatus.CONFIRMING))
        }
        assertEquals(PaymentOrderStatus.CONFIRMING, store.findOrder(first.id)!!.status)

        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx ->
                val locked = assertNotNull(tx.lockOrder(first.id))
                tx.updateOrder(locked.copy(status = PaymentOrderStatus.PAID))
                error("boom")
            }
        }
        assertEquals(PaymentOrderStatus.CONFIRMING, store.findOrder(first.id)!!.status)
    }

    @Test
    fun `locking a second order in one transaction is refused - one order per transaction keeps lock order trivial`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        store.inTransaction { it.insertOrderIfAbsent(first) }
        store.inTransaction { it.insertOrderIfAbsent(other) }

        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx ->
                tx.lockOrder(first.id)
                tx.lockOrder(other.id)
            }
        }
    }

    @Test
    fun `events are listed in the order they were appended and only for their order`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }

        store.inTransaction { tx ->
            tx.appendEvent(event(first.id, PaymentEventType.ORDER_CREATED))
            tx.appendEvent(event(first.id, PaymentEventType.CONFIRM_STARTED))
            tx.appendEvent(event("ord_other", PaymentEventType.ORDER_CREATED))
        }

        assertEquals(
            listOf(PaymentEventType.ORDER_CREATED, PaymentEventType.CONFIRM_STARTED),
            store.listEvents(first.id).map { it.type },
        )
    }

    @Test
    fun `a provider payment id cannot belong to two orders`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        store.inTransaction { it.insertOrderIfAbsent(first) }
        store.inTransaction { it.insertOrderIfAbsent(other) }
        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(first.id)!!.copy(providerPaymentId = "pay_1")) }

        assertFailsWith<PaymentLedgerConflictException> {
            store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(other.id)!!.copy(providerPaymentId = "pay_1")) }
        }
        assertNull(store.findOrder(other.id)!!.providerPaymentId)
        assertEquals(first.id, store.inTransaction { it.findOrderByProviderPayment("scripted", "pay_1") }!!.id)
        assertNull(store.inTransaction { it.findOrderByProviderPayment("another", "pay_1") }, "the unique key is per provider")
    }

    @Test
    fun `two transactions on one order run one after the other and the second sees the first`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }
        val firstHolds = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondSaw = AtomicInteger(-1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit {
                store.inTransaction { tx ->
                    val locked = tx.lockOrder(first.id)!!
                    firstHolds.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                    tx.updateOrder(locked.copy(refundedAmount = 7))
                }
            }
            firstHolds.await(5, TimeUnit.SECONDS)
            val b = pool.submit {
                store.inTransaction { tx -> secondSaw.set(tx.lockOrder(first.id)!!.refundedAmount.toInt()) }
            }
            Thread.sleep(150)
            assertFalse(b.isDone, "the second transaction waits for the lock")
            releaseFirst.countDown()
            a.get(5, TimeUnit.SECONDS)
            b.get(5, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        assertEquals(7, secondSaw.get(), "the lock re-reads the row after the wait")
    }

    @Test
    fun `sixteen threads inserting one dedupe key create exactly one row`() {
        val store = newStore()
        val inserted = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..16).map { n ->
                pool.submit {
                    start.await()
                    val order = first.copy(id = "ord_" + n.toString(16).padStart(32, '0'))
                    if (store.inTransaction { it.insertOrderIfAbsent(order) }) inserted.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, inserted.get())
    }

    @Test
    fun `every column of an order and of an event survives the round trip - insert, update and read back`() {
        val store = newStore()
        val full = fullOrder()

        assertTrue(store.inTransaction { it.insertOrderIfAbsent(full) })
        assertEquals(full, store.findOrder(full.id), "insert then read")
        assertEquals(full, store.findOrderByDedupeKey("dedupe-full"))

        val changed = full.copy(
            status = PaymentOrderStatus.PARTIALLY_REFUNDED,
            providerPaymentId = "pay_changed",
            paidAmount = 2_900,
            taxAmount = 263,
            refundedAmount = 1_000,
            method = "EASY_PAY",
            cardIssuer = "SAMSUNG",
            cardLast4 = "12*4",
            failureCode = "OTHER_CODE",
            reviewReason = PaymentReviewReason.REFUND_MISMATCH,
            confirmStartedAt = full.confirmStartedAt!!.plusSeconds(60),
            paidAt = full.paidAt!!.plusSeconds(60),
            fulfilledAt = full.fulfilledAt!!.plusSeconds(60),
            closedAt = full.closedAt!!.plusSeconds(60),
            accountErasedAt = full.accountErasedAt!!.plusSeconds(60),
            updatedAt = full.updatedAt.plusSeconds(60),
        )
        store.inTransaction { tx ->
            tx.lockOrder(full.id)
            tx.updateOrder(changed)
        }
        assertEquals(changed, store.findOrder(full.id), "update then read")
        assertEquals(changed, store.inTransaction { it.lockOrder(full.id) }, "the locked read sees the same row")

        val fullEvent = PaymentEvent(
            orderId = full.id,
            type = PaymentEventType.CONVERGED,
            actor = PaymentActor.PROVIDER,
            createdAt = PaymentTestOrders.T0.plusSeconds(7),
            refundId = "ref_" + "9".repeat(32),
            fromStatus = "CONFIRMING",
            toStatus = "PAID",
            actorId = "acc_1",
            providerCode = "DONE",
            providerTraceId = "trace-provider",
            traceId = "trace-ours",
        )
        store.inTransaction { it.appendEvent(fullEvent) }
        assertEquals(listOf(fullEvent), store.listEvents(full.id))
    }

    @Test
    fun `sixteen threads taking one provider payment id leave exactly one owner and fifteen conflicts`() {
        val store = newStore()
        val ids = (1..16).map { n -> "ord_" + n.toString(16).padStart(32, '0') }
        ids.forEach { id -> store.inTransaction { it.insertOrderIfAbsent(first.copy(id = id, dedupeKey = null)) } }
        val won = AtomicInteger()
        val conflicted = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = ids.map { id ->
                pool.submit {
                    start.await()
                    try {
                        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(id)!!.copy(providerPaymentId = "pay_shared")) }
                        won.incrementAndGet()
                    } catch (e: PaymentLedgerConflictException) {
                        conflicted.incrementAndGet()
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, won.get())
        assertEquals(15, conflicted.get())
        assertEquals(1, ids.count { store.findOrder(it)!!.providerPaymentId == "pay_shared" })
    }

    @Test
    fun `a provider payment id taken in a transaction that rolls back is free again`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        store.inTransaction { it.insertOrderIfAbsent(first) }
        store.inTransaction { it.insertOrderIfAbsent(other) }

        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx ->
                tx.updateOrder(tx.lockOrder(first.id)!!.copy(providerPaymentId = "pay_1"))
                error("boom")
            }
        }

        assertNull(store.findOrder(first.id)!!.providerPaymentId)
        assertNull(store.inTransaction { it.findOrderByProviderPayment("scripted", "pay_1") }, "nobody owns the key after the rollback")
        store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(other.id)!!.copy(providerPaymentId = "pay_1")) }
        assertEquals(other.id, store.inTransaction { it.findOrderByProviderPayment("scripted", "pay_1") }!!.id)
    }

    @Test
    fun `hasOpenTransaction is true only for the thread inside a transaction, nested ones included`() {
        val store = newStore()
        assertFalse(store.hasOpenTransaction(), "before")

        store.inTransaction {
            assertTrue(store.hasOpenTransaction(), "inside")
            store.inTransaction { assertTrue(store.hasOpenTransaction(), "inside a nested one that joins") }
            assertTrue(store.hasOpenTransaction(), "still inside after the nested one")
            val seenByOtherThread = Executors.newSingleThreadExecutor().let { pool ->
                try {
                    pool.submit<Boolean> { store.hasOpenTransaction() }.get(5, TimeUnit.SECONDS)
                } finally {
                    pool.shutdownNow()
                }
            }
            assertFalse(seenByOtherThread, "another thread has no transaction of its own")
        }
        assertFalse(store.hasOpenTransaction(), "after the commit")

        assertFailsWith<IllegalStateException> { store.inTransaction { error("boom") } }
        assertFalse(store.hasOpenTransaction(), "after the rollback")
    }

    // --- 환불 줄 (B5) ---

    private val refundA get() = PaymentTestOrders.refund(id = "rfd_" + "1".repeat(32), orderId = first.id, dedupeKey = "rd-1")

    /** 주문을 넣어 두고 그 주문을 잠근 트랜잭션에서 [block] 을 돈다. */
    private fun <T> withOrder(store: PaymentLedgerStore, order: PaymentOrder = first, block: (PaymentLedgerTx) -> T): T {
        if (store.findOrder(order.id) == null) store.inTransaction { it.insertOrderIfAbsent(order) }
        return store.inTransaction { tx ->
            tx.lockOrder(order.id)
            block(tx)
        }
    }

    @Test
    fun `every column of a refund survives the round trip - insert, update and read back through every reader`() {
        val store = newStore()
        val full = PaymentRefund(
            id = "rfd_" + "4".repeat(32),
            orderId = first.id,
            status = PaymentRefundStatus.PENDING,
            origin = PaymentRefundOrigin.OPERATOR,
            amount = PaymentAmount(1_234, "KRW"),
            reasonCode = "DUPLICATE",
            openKey = first.id,
            dedupeKey = "rd-full",
            providerRefundId = "txn_full",
            expectedRemaining = 1_766,
            requestedBy = "acc_req",
            decidedBy = "acc_dec",
            failureCode = "SOME_CODE",
            requestedAt = PaymentTestOrders.T0.plusSeconds(1),
            sentAt = PaymentTestOrders.T0.plusSeconds(2),
            completedAt = PaymentTestOrders.T0.plusSeconds(3),
            appliedAt = PaymentTestOrders.T0.plusSeconds(4),
            createdAt = PaymentTestOrders.T0.plusSeconds(5),
            updatedAt = PaymentTestOrders.T0.plusSeconds(6),
        )

        assertTrue(withOrder(store) { it.insertRefundIfAbsent(full) })
        assertEquals(full, store.findRefund(full.id), "insert then read")
        assertEquals(full, store.findRefundByDedupeKey("rd-full"))
        assertEquals(listOf(full), store.listRefunds(first.id))

        val changed = full.copy(
            status = PaymentRefundStatus.SUCCEEDED,
            origin = PaymentRefundOrigin.PROVIDER,
            amount = PaymentAmount(1_300, "KRW"),
            reasonCode = "PROVIDER_CONSOLE",
            openKey = null,
            providerRefundId = "txn_changed",
            expectedRemaining = 1_700,
            requestedBy = null,
            decidedBy = "acc_dec2",
            failureCode = "OTHER_CODE",
            sentAt = full.sentAt!!.plusSeconds(60),
            completedAt = full.completedAt!!.plusSeconds(60),
            appliedAt = full.appliedAt!!.plusSeconds(60),
            updatedAt = full.updatedAt.plusSeconds(60),
        )
        withOrder(store) { tx -> tx.updateRefund(changed) }
        assertEquals(changed, store.findRefund(full.id), "update then read")
        assertEquals(changed, withOrder(store) { it.lockRefund(full.id) }, "the in-transaction read sees the same row")
        assertEquals(listOf(changed), withOrder(store) { it.listRefunds(first.id) })
    }

    @Test
    fun `a refund line is refused when its dedupe key exists - even for another order - and the first line stays`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        assertTrue(withOrder(store) { it.insertRefundIfAbsent(refundA) })

        val sameKeyOtherOrder = refundA.copy(id = "rfd_" + "2".repeat(32), orderId = other.id, openKey = other.id)
        assertFalse(withOrder(store, other) { it.insertRefundIfAbsent(sameKeyOtherOrder) })

        assertEquals(refundA, store.findRefundByDedupeKey("rd-1"))
        assertNull(store.findRefund(sameKeyOtherOrder.id))
        assertEquals(emptyList(), store.listRefunds(other.id))
    }

    @Test
    fun `an order has one open refund slot - a second open line is refused and closing the first frees the slot`() {
        val store = newStore()
        assertTrue(withOrder(store) { it.insertRefundIfAbsent(refundA) })

        val second = PaymentTestOrders.refund(id = "rfd_" + "2".repeat(32), orderId = first.id, dedupeKey = "rd-2", status = PaymentRefundStatus.PENDING)
        assertFalse(withOrder(store) { it.insertRefundIfAbsent(second) }, "REQUESTED holds the slot")

        withOrder(store) { tx -> tx.updateRefund(tx.lockRefund(refundA.id)!!.copy(status = PaymentRefundStatus.WITHDRAWN, openKey = null)) }
        assertTrue(withOrder(store) { it.insertRefundIfAbsent(second) }, "a closed line leaves the slot free")
        assertEquals(listOf(refundA.id, second.id), store.listRefunds(first.id).map { it.id })
    }

    @Test
    fun `a provider refund id belongs to one line per order and may repeat on another order`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        val closed = PaymentTestOrders.refund(id = "rfd_" + "1".repeat(32), orderId = first.id, status = PaymentRefundStatus.SUCCEEDED, origin = PaymentRefundOrigin.PROVIDER, providerRefundId = "txn_1")
        assertTrue(withOrder(store) { it.insertRefundIfAbsent(closed) })

        val again = closed.copy(id = "rfd_" + "2".repeat(32))
        assertFalse(withOrder(store) { it.insertRefundIfAbsent(again) }, "the same cancel cannot be put in twice")
        val onOther = closed.copy(id = "rfd_" + "3".repeat(32), orderId = other.id)
        assertTrue(withOrder(store, other) { it.insertRefundIfAbsent(onOther) }, "the unique key is per order")

        val pending = PaymentTestOrders.refund(id = "rfd_" + "5".repeat(32), orderId = first.id, status = PaymentRefundStatus.PENDING, dedupeKey = "rd-5")
        assertTrue(withOrder(store) { it.insertRefundIfAbsent(pending) })
        assertFailsWith<PaymentLedgerConflictException> {
            withOrder(store) { tx -> tx.updateRefund(tx.lockRefund(pending.id)!!.copy(providerRefundId = "txn_1")) }
        }
        assertNull(store.findRefund(pending.id)!!.providerRefundId)
    }

    @Test
    fun `a refund cannot be written unless its order is locked in this transaction`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        store.inTransaction { it.insertOrderIfAbsent(first) }
        store.inTransaction { it.insertOrderIfAbsent(other) }
        withOrder(store) { it.insertRefundIfAbsent(refundA) }

        assertFailsWith<IllegalStateException> { store.inTransaction { tx -> tx.insertRefundIfAbsent(refundA.copy(id = "rfd_" + "2".repeat(32), dedupeKey = "rd-2")) } }
        assertFailsWith<IllegalStateException> { store.inTransaction { tx -> tx.updateRefund(refundA.copy(failureCode = "X")) } }
        assertFailsWith<IllegalStateException> { store.inTransaction { tx -> tx.lockRefund(refundA.id) } }
        assertFailsWith<IllegalStateException> { store.inTransaction { tx -> tx.listRefunds(first.id) } }
        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx ->
                tx.lockOrder(other.id)
                tx.updateRefund(refundA.copy(failureCode = "X")) // 다른 주문을 잠갔다
            }
        }
        assertEquals(refundA, store.findRefund(refundA.id), "nothing was written")
    }

    @Test
    fun `updating a refund that does not exist fails and a missing refund reads as null`() {
        val store = newStore()

        assertFailsWith<IllegalStateException> { withOrder(store) { it.updateRefund(refundA) } }
        assertNull(withOrder(store) { it.lockRefund("rfd_missing") })
        assertNull(store.findRefund("rfd_missing"))
        assertNull(store.findRefundByDedupeKey("nope"))
    }

    @Test
    fun `refund writes roll back with the transaction and release the open slot and the dedupe key`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }

        assertFailsWith<IllegalArgumentException> {
            store.inTransaction { tx ->
                tx.lockOrder(first.id)
                tx.insertRefundIfAbsent(refundA)
                assertEquals(refundA, tx.findRefundByDedupeKey("rd-1"), "visible to its own transaction")
                assertEquals(listOf(refundA), tx.listRefunds(first.id))
                throw IllegalArgumentException("boom")
            }
        }

        assertNull(store.findRefund(refundA.id))
        assertNull(store.findRefundByDedupeKey("rd-1"))
        assertTrue(withOrder(store) { it.insertRefundIfAbsent(refundA) }, "the slot and the key were released")
    }

    @Test
    fun `refund lines are listed oldest first and only for their order`() {
        val store = newStore()
        val other = first.copy(id = "ord_" + "2".repeat(32), dedupeKey = null)
        val later = PaymentTestOrders.refund(id = "rfd_" + "0".repeat(32), orderId = first.id, status = PaymentRefundStatus.FAILED, now = PaymentTestOrders.T0.plusSeconds(10))
        val earlier = PaymentTestOrders.refund(id = "rfd_" + "9".repeat(32), orderId = first.id, status = PaymentRefundStatus.FAILED, now = PaymentTestOrders.T0)
        withOrder(store) { tx ->
            tx.insertRefundIfAbsent(later)
            tx.insertRefundIfAbsent(earlier)
        }
        withOrder(store, other) { it.insertRefundIfAbsent(PaymentTestOrders.refund(id = "rfd_" + "7".repeat(32), orderId = other.id, status = PaymentRefundStatus.FAILED)) }

        assertEquals(listOf(earlier.id, later.id), store.listRefunds(first.id).map { it.id })
        assertEquals(listOf(earlier.id, later.id), withOrder(store) { tx -> tx.listRefunds(first.id).map { it.id } })
    }

    @Test
    fun `sixteen transactions each trying to open a refund on one order leave exactly one open line`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }
        val won = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..16).map { n ->
                pool.submit {
                    start.await()
                    val line = PaymentTestOrders.refund(id = "rfd_" + n.toString(16).padStart(32, '0'), orderId = first.id, status = PaymentRefundStatus.PENDING, dedupeKey = "rd-race-$n")
                    if (withOrder(store) { it.insertRefundIfAbsent(line) }) won.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, won.get())
        assertEquals(1, store.listRefunds(first.id).size)
    }

    @Test
    fun `an event keeps the refund it is about`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }
        val about = PaymentEvent(first.id, PaymentEventType.REFUND_SENT, PaymentActor.OPERATOR, PaymentTestOrders.T0, refundId = "rfd_" + "1".repeat(32), fromStatus = "REQUESTED", toStatus = "PENDING")

        store.inTransaction { it.appendEvent(about) }

        assertEquals(listOf(about), store.listEvents(first.id))
    }

    // ---- 웹훅 수신함 ----

    private fun hook(key: String = "evt-1", at: Instant = PaymentTestOrders.T0, status: PaymentWebhookStatus = PaymentWebhookStatus.RECEIVED, provider: String = "scripted") =
        PaymentTestOrders.webhookEvent(provider = provider, eventKey = key, receivedAt = at, status = status)

    @Test
    fun `the first delivery of an event inserts the row and counts one`() {
        val store = newStore()

        val recorded = store.inTransaction { it.recordWebhookDelivery(hook()) }

        assertTrue(recorded.first)
        assertEquals(1, recorded.event.deliveries)
        assertTrue(recorded.event.id > 0)
        assertEquals(recorded.event, store.findWebhookEvent(recorded.event.id))
    }

    @Test
    fun `a repeated delivery counts up and leaves the stored row as it was`() {
        val store = newStore()
        val firstTime = store.inTransaction { it.recordWebhookDelivery(hook()) }

        val again = store.inTransaction { it.recordWebhookDelivery(hook().copy(eventType = "OTHER", payloadHash = "b".repeat(64), status = PaymentWebhookStatus.DEAD)) }

        assertFalse(again.first)
        assertEquals(firstTime.event.id, again.event.id)
        assertEquals(2, again.event.deliveries)
        assertEquals(firstTime.event.copy(deliveries = 2), again.event, "only the counter moved")
        assertEquals(again.event, store.findWebhookEvent(firstTime.event.id))
    }

    @Test
    fun `the same event key of another provider is another row`() {
        val store = newStore()

        val a = store.inTransaction { it.recordWebhookDelivery(hook(provider = "scripted")) }
        val b = store.inTransaction { it.recordWebhookDelivery(hook(provider = "another")) }

        assertTrue(a.first && b.first)
        assertTrue(a.event.id != b.event.id)
    }

    @Test
    fun `a delivery recorded in a transaction that rolls back leaves no row and no count`() {
        val store = newStore()
        store.inTransaction { it.recordWebhookDelivery(hook(key = "kept")) }

        assertFailsWith<IllegalArgumentException> {
            store.inTransaction { tx ->
                tx.recordWebhookDelivery(hook(key = "kept"))
                throw IllegalArgumentException("boom")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            store.inTransaction { tx ->
                tx.recordWebhookDelivery(hook(key = "ghost"))
                throw IllegalArgumentException("boom")
            }
        }

        assertEquals(1, store.listWebhookEvents(null, null, 10).single().deliveries, "one row (the rolled back first delivery left none) counted once (the rolled back repeats did not count)")
    }

    @Test
    fun `sixteen threads recording one event key leave one row counted sixteen times and exactly one first delivery`() {
        val store = newStore()
        val firsts = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        try {
            val futures = (1..16).map {
                pool.submit {
                    start.await()
                    if (store.inTransaction { tx -> tx.recordWebhookDelivery(hook()) }.first) firsts.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val rows = store.listWebhookEvents(null, null, 10)
        assertEquals(1, rows.size)
        assertEquals(16, rows.single().deliveries)
        assertEquals(1, firsts.get())
    }

    @Test
    fun `a webhook row cannot be written unless it was recorded or locked in this transaction`() {
        val store = newStore()
        val row = store.inTransaction { it.recordWebhookDelivery(hook()) }.event

        assertFailsWith<IllegalStateException> { store.inTransaction { it.updateWebhookEvent(row.copy(status = PaymentWebhookStatus.PROCESSED)) } }

        assertEquals(PaymentWebhookStatus.RECEIVED, store.findWebhookEvent(row.id)!!.status)
    }

    @Test
    fun `a locked webhook row is re-read and updated - status, error and processed time change, nothing else does`() {
        val store = newStore()
        val row = store.inTransaction { it.recordWebhookDelivery(hook().copy(orderId = null, payload = "keep-me")) }.event
        val done = PaymentTestOrders.T0.plusSeconds(30)

        store.inTransaction { tx ->
            val locked = checkNotNull(tx.lockWebhookEvent(row.id))
            tx.updateWebhookEvent(locked.copy(status = PaymentWebhookStatus.IGNORED, lastError = "UNKNOWN_ORDER", processedAt = done, eventType = "CHANGED", deliveries = 99, payloadHash = "c".repeat(64)))
        }

        val stored = checkNotNull(store.findWebhookEvent(row.id))
        assertEquals(PaymentWebhookStatus.IGNORED, stored.status)
        assertEquals("UNKNOWN_ORDER", stored.lastError)
        assertEquals(done, stored.processedAt)
        assertEquals(row.copy(status = PaymentWebhookStatus.IGNORED, lastError = "UNKNOWN_ORDER", processedAt = done), stored, "event type, delivery count and hash are the first delivery's")
    }

    @Test
    fun `one webhook row per transaction - and an order can only be locked before it, never after`() {
        val store = newStore()
        store.inTransaction { it.insertOrderIfAbsent(first) }
        val a = store.inTransaction { it.recordWebhookDelivery(hook(key = "a")) }.event
        val b = store.inTransaction { it.recordWebhookDelivery(hook(key = "b")) }.event

        assertFailsWith<IllegalStateException> {
            store.inTransaction { tx ->
                tx.lockWebhookEvent(a.id)
                tx.lockWebhookEvent(b.id)
            }
        }
        assertFailsWith<IllegalStateException>("lock order is order, then webhook row") {
            store.inTransaction { tx ->
                tx.lockWebhookEvent(a.id)
                tx.lockOrder(first.id)
            }
        }
        store.inTransaction { tx ->
            assertNotNull(tx.lockOrder(first.id))
            assertNotNull(tx.lockWebhookEvent(a.id))
        }
    }

    @Test
    fun `the inbox lists newest first with a cursor, filters by status, and an unfiltered list merges every status`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val statuses = listOf(PaymentWebhookStatus.RECEIVED, PaymentWebhookStatus.PROCESSED, PaymentWebhookStatus.FAILED, PaymentWebhookStatus.PROCESSED, PaymentWebhookStatus.DEAD, PaymentWebhookStatus.IGNORED, PaymentWebhookStatus.PROCESSED)
        statuses.forEachIndexed { i, status ->
            store.inTransaction { it.recordWebhookDelivery(hook(key = "e$i", at = t.plusSeconds(i.toLong()), status = status)) }
        }
        // 같은 시각이면 id 가 큰 쪽이 먼저
        store.inTransaction { it.recordWebhookDelivery(hook(key = "tie", at = t.plusSeconds(6), status = PaymentWebhookStatus.PROCESSED)) }

        val all = store.listWebhookEvents(null, null, 100)
        assertEquals(listOf("tie", "e6", "e5", "e4", "e3", "e2", "e1", "e0"), all.map { it.eventKey })

        val page1 = store.listWebhookEvents(null, null, 3)
        val last = page1.last()
        val page2 = store.listWebhookEvents(null, WebhookEventCursor(last.receivedAt, last.id), 3)
        assertEquals(listOf("tie", "e6", "e5"), page1.map { it.eventKey })
        assertEquals(listOf("e4", "e3", "e2"), page2.map { it.eventKey })

        assertEquals(listOf("tie", "e6", "e3", "e1"), store.listWebhookEvents(PaymentWebhookStatus.PROCESSED, null, 10).map { it.eventKey })
        val processedPage = store.listWebhookEvents(PaymentWebhookStatus.PROCESSED, WebhookEventCursor(t.plusSeconds(6), all.first { it.eventKey == "e6" }.id), 10)
        assertEquals(listOf("e3", "e1"), processedPage.map { it.eventKey })
        assertEquals(emptyList(), store.listWebhookEvents(PaymentWebhookStatus.DEAD, WebhookEventCursor(t.plusSeconds(4), all.first { it.eventKey == "e4" }.id), 10).map { it.eventKey })
    }

    @Test
    fun `an inbox row is found by its provider and event key - the same key of another provider is another row`() {
        val store = newStore()
        val a = store.inTransaction { it.recordWebhookDelivery(hook(key = "k1", provider = "scripted")) }.event

        assertEquals(a, store.findWebhookEventByKey("scripted", "k1"))
        assertNull(store.findWebhookEventByKey("another", "k1"))
        assertNull(store.findWebhookEventByKey("scripted", "k2"))
    }

    @Test
    fun `the window count is per order and resource type, counts rows received at or after the start of the window, every status and every resource id - and needs the order locked`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val other = PaymentTestOrders.order(id = "ord_" + "2".repeat(32), dedupeKey = "dedupe-2")
        store.inTransaction { it.insertOrderIfAbsent(first) }
        store.inTransaction { it.insertOrderIfAbsent(other) }
        val quiet = PaymentTestOrders.order(id = "ord_" + "3".repeat(32), dedupeKey = "dedupe-3")
        store.inTransaction { it.insertOrderIfAbsent(quiet) }
        fun put(key: String, at: Instant, order: String = first.id, resource: String = "pay_1", type: String = "PAYMENT", status: PaymentWebhookStatus = PaymentWebhookStatus.RECEIVED) {
            store.inTransaction { it.recordWebhookDelivery(PaymentTestOrders.webhookEvent(eventKey = key, resourceId = resource, orderId = order, receivedAt = at, status = status).copy(resourceType = type)) }
        }
        fun count(order: String, type: String, since: Instant) = store.inTransaction { tx ->
            tx.lockOrder(order)
            tx.countWebhookEventsOfOrder(order, type, since)
        }
        put("old", t.minusSeconds(1))
        put("edge", t)
        put("later", t.plusSeconds(30), status = PaymentWebhookStatus.PROCESSED)
        put("dead", t.plusSeconds(31), status = PaymentWebhookStatus.DEAD)
        put("order:resource", t.plusSeconds(30), resource = "order:${first.id}") // the same order under its other resource id: still the same order
        put("other-order", t.plusSeconds(30), order = other.id)
        put("marker", t.plusSeconds(30), type = "LIMIT_MARKER")
        store.inTransaction { it.recordWebhookDelivery(PaymentTestOrders.webhookEvent(eventKey = "edge", orderId = first.id)) } // 같은 줄을 다시 센 것은 줄을 늘리지 않는다

        assertEquals(4, count(first.id, "PAYMENT", t))
        assertEquals(5, count(first.id, "PAYMENT", t.minusSeconds(1)))
        assertEquals(3, count(first.id, "PAYMENT", t.plusSeconds(1)))
        assertEquals(0, count(first.id, "PAYMENT", t.plusSeconds(32)))
        assertEquals(1, count(other.id, "PAYMENT", t))
        assertEquals(1, count(first.id, "LIMIT_MARKER", t))
        assertEquals(0, count(quiet.id, "PAYMENT", t))
        assertFailsWith<IllegalStateException>("the count is taken under the order lock") { store.inTransaction { it.countWebhookEventsOfOrder(first.id, "PAYMENT", t) } }
    }

    @Test
    fun `the unfiltered list is one statement by id - newest insert first, the cursor is its id - even when received times run backwards`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        // 시계가 거꾸로 간 줄 둘 — 받은 시각 순과 id 순이 다르다
        listOf("a" to 10L, "b" to 5L, "c" to 20L, "d" to 1L).forEach { (key, sec) -> store.inTransaction { it.recordWebhookDelivery(hook(key = key, at = t.plusSeconds(sec))) } }

        val all = store.listWebhookEvents(null, null, 100)
        assertEquals(listOf("d", "c", "b", "a"), all.map { it.eventKey })

        val first = store.listWebhookEvents(null, null, 2)
        val second = store.listWebhookEvents(null, WebhookEventCursor(first.last().receivedAt, first.last().id), 2)
        assertEquals(listOf("d", "c"), first.map { it.eventKey })
        assertEquals(listOf("b", "a"), second.map { it.eventKey }, "no row twice, none missing")
    }

    @Test
    fun `every column of an inbox row survives the round trip`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        store.inTransaction { it.insertOrderIfAbsent(first) }
        val candidate = PaymentWebhookEvent(
            id = 0, provider = "scripted", eventKey = "evt-full", eventType = "PAYMENT_STATUS_CHANGED", resourceType = "PAYMENT", resourceId = "pay_full", orderId = first.id,
            payload = "a body\nof several lines", payloadHash = "d".repeat(64), status = PaymentWebhookStatus.RECEIVED, deliveries = 0, lastError = null, receivedAt = t.plusSeconds(5), processedAt = null,
        )
        val id = store.inTransaction { it.recordWebhookDelivery(candidate) }.event.id

        store.inTransaction { tx ->
            val locked = checkNotNull(tx.lockWebhookEvent(id))
            tx.updateWebhookEvent(locked.copy(status = PaymentWebhookStatus.FAILED, lastError = "LOOKUP_UNAVAILABLE", processedAt = t.plusSeconds(9)))
        }

        assertEquals(
            candidate.copy(id = id, deliveries = 1, status = PaymentWebhookStatus.FAILED, lastError = "LOOKUP_UNAVAILABLE", processedAt = t.plusSeconds(9)),
            store.findWebhookEvent(id),
        )
    }

    @Test
    fun `an order is found by provider and provider payment id outside a transaction too`() {
        val store = newStore()
        val paid = first.copy(providerPaymentId = "pay_known")
        store.inTransaction { it.insertOrderIfAbsent(paid.copy(providerPaymentId = null)) }
        store.inTransaction { tx -> tx.lockOrder(paid.id); tx.updateOrder(paid) }

        assertEquals(paid.id, store.findOrderByProviderPayment(paid.provider, "pay_known")?.id)
        assertNull(store.findOrderByProviderPayment(paid.provider, "pay_unknown"))
        assertNull(store.findOrderByProviderPayment("another", "pay_known"))
    }

    // ---------------------------------------------------------------------------------------------------------------------------------
    // 목록 읽기 (B7 — HTTP). 메모리 대역과 두 방언이 같은 순서 · 같은 커서 · 같은 필터를 지킨다.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private fun orderAt(n: Int, accountId: String = "acc_1", status: PaymentOrderStatus = PaymentOrderStatus.PAID, at: Instant = PaymentTestOrders.T0.plusSeconds(n.toLong()), provider: String = "scripted") =
        PaymentTestOrders.order(id = "ord_" + n.toString().padStart(32, '0'), accountId = accountId, status = status, now = at, provider = provider)

    private fun insert(store: PaymentLedgerStore, vararg orders: PaymentOrder) = orders.forEach { o -> assertTrue(store.inTransaction { it.insertOrderIfAbsent(o) }) }

    private fun insertRefund(store: PaymentLedgerStore, refund: PaymentRefund) {
        assertTrue(store.inTransaction { tx -> tx.lockOrder(refund.orderId); tx.insertRefundIfAbsent(refund) })
    }

    @Test
    fun `an account's orders come newest first without the unpaid attempts, and only its own`() {
        val store = newStore()
        insert(
            store,
            orderAt(1), orderAt(2, status = PaymentOrderStatus.PENDING), orderAt(3), orderAt(4, accountId = "acc_other"),
            orderAt(5, status = PaymentOrderStatus.CONFIRMING), orderAt(6, status = PaymentOrderStatus.FAILED),
        )

        val listed = store.listAccountOrders("acc_1", null, 10)

        assertEquals(listOf(6, 5, 3, 1).map { orderAt(it).id }, listed.map { it.id })
    }

    @Test
    fun `an account's order list pages by a cursor that skips the cursor row - ties on the created time break by id, the larger id first`() {
        val store = newStore()
        val same = PaymentTestOrders.T0.plusSeconds(100)
        insert(store, orderAt(1, at = same), orderAt(2, at = same), orderAt(3, at = same), orderAt(4, at = same.plusSeconds(1)), orderAt(5, at = same.minusSeconds(1)))

        val firstPage = store.listAccountOrders("acc_1", null, 2)
        val secondPage = store.listAccountOrders("acc_1", OrderCursor(firstPage.last().createdAt, firstPage.last().id), 2)
        val thirdPage = store.listAccountOrders("acc_1", OrderCursor(secondPage.last().createdAt, secondPage.last().id), 2)

        assertEquals(listOf(4, 3), firstPage.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(2, 1), secondPage.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(5), thirdPage.map { it.id.takeLast(2).toInt() })
    }

    @Test
    fun `the operator list without a scope is newest first and the filters narrow it - status, account, and erased accounts hidden on request`() {
        val store = newStore()
        val erased = orderAt(3).copy(accountErasedAt = PaymentTestOrders.T0.plusSeconds(500))
        insert(store, orderAt(1), orderAt(2, accountId = "acc_2", status = PaymentOrderStatus.PENDING), erased, orderAt(4, accountId = "acc_2"))

        fun ids(filter: OperatorOrderFilter) = store.listOrdersForOperator(OperatorOrderScope.ALL, filter, null, null, 10).map { it.id.takeLast(2).toInt() }

        assertEquals(listOf(4, 3, 2, 1), ids(OperatorOrderFilter()))
        assertEquals(listOf(2), ids(OperatorOrderFilter(status = PaymentOrderStatus.PENDING)))
        assertEquals(listOf(4, 2), ids(OperatorOrderFilter(accountId = "acc_2")))
        assertEquals(listOf(4, 2, 1), ids(OperatorOrderFilter(hideErased = true)))
        assertEquals(listOf(2), ids(OperatorOrderFilter(status = PaymentOrderStatus.PENDING, accountId = "acc_2", hideErased = true)))
    }

    @Test
    fun `the operator list pages by a cursor - including the pending attempts the user list leaves out`() {
        val store = newStore()
        insert(store, *(1..5).map { orderAt(it, status = if (it % 2 == 0) PaymentOrderStatus.PENDING else PaymentOrderStatus.PAID) }.toTypedArray())

        val firstPage = store.listOrdersForOperator(OperatorOrderScope.ALL, OperatorOrderFilter(), null, null, 2)
        val secondPage = store.listOrdersForOperator(OperatorOrderScope.ALL, OperatorOrderFilter(), null, OrderCursor(firstPage.last().createdAt, firstPage.last().id), 2)
        val thirdPage = store.listOrdersForOperator(OperatorOrderScope.ALL, OperatorOrderFilter(), null, OrderCursor(secondPage.last().createdAt, secondPage.last().id), 2)

        assertEquals(listOf(5, 4), firstPage.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(3, 2), secondPage.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(1), thirdPage.map { it.id.takeLast(2).toInt() })
    }

    @Test
    fun `the review scope lists the rows with a review reason - newest first, filters and a cursor apply`() {
        val store = newStore()
        insert(
            store,
            orderAt(1).copy(reviewReason = PaymentReviewReason.AMOUNT_MISMATCH), orderAt(2), orderAt(3).copy(reviewReason = PaymentReviewReason.REFUND_MISMATCH),
            orderAt(4, accountId = "acc_2").copy(reviewReason = PaymentReviewReason.MODE_MISMATCH),
        )

        fun ids(filter: OperatorOrderFilter = OperatorOrderFilter(), before: OrderCursor? = null, limit: Int = 10) =
            store.listOrdersForOperator(OperatorOrderScope.REVIEW_REASON, filter, null, before, limit).map { it.id.takeLast(2).toInt() }

        assertEquals(listOf(4, 3, 1), ids())
        assertEquals(listOf(4), ids(OperatorOrderFilter(accountId = "acc_2")))
        assertEquals(listOf(3, 1), ids(before = OrderCursor(orderAt(4).createdAt, orderAt(4).id)))
        assertEquals(listOf(4, 3), ids(limit = 2))
    }

    @Test
    fun `the stale-confirming scope lists the rows still CONFIRMING since before the threshold - a younger one and other statuses stay out`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        insert(
            store,
            orderAt(1, status = PaymentOrderStatus.CONFIRMING).copy(confirmStartedAt = t.plusSeconds(10)),
            orderAt(2, status = PaymentOrderStatus.CONFIRMING).copy(confirmStartedAt = t.plusSeconds(5_000)),
            orderAt(3, status = PaymentOrderStatus.PAID).copy(confirmStartedAt = t.plusSeconds(10)),
            orderAt(4, status = PaymentOrderStatus.CONFIRMING).copy(confirmStartedAt = t.plusSeconds(20)),
        )

        val listed = store.listOrdersForOperator(OperatorOrderScope.STALE_CONFIRMING, OperatorOrderFilter(), t.plusSeconds(1_000), null, 10)

        assertEquals(listOf(4, 1), listed.map { it.id.takeLast(2).toInt() })
    }

    @Test
    fun `the unfulfilled scope lists paid rows with no fulfilment time that were paid before the threshold - fully refunded and fulfilled rows stay out`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        insert(
            store,
            orderAt(1).copy(paidAt = t.plusSeconds(10)),
            orderAt(2).copy(paidAt = t.plusSeconds(10), fulfilledAt = t.plusSeconds(11)),
            orderAt(3, status = PaymentOrderStatus.PARTIALLY_REFUNDED).copy(paidAt = t.plusSeconds(10)),
            orderAt(4, status = PaymentOrderStatus.REFUNDED).copy(paidAt = t.plusSeconds(10)),
            orderAt(5).copy(paidAt = t.plusSeconds(5_000)),
            orderAt(6, status = PaymentOrderStatus.CONFIRMING),
            orderAt(7).copy(paidAt = null),
        )

        val listed = store.listOrdersForOperator(OperatorOrderScope.UNFULFILLED, OperatorOrderFilter(), t.plusSeconds(1_000), null, 10)

        assertEquals(listOf(7, 3, 1), listed.map { it.id.takeLast(2).toInt() })
    }

    @Test
    fun `the unfulfilled scope applies the account filter, the erased filter, the status filter, the cursor and the limit to the unfulfilled orders - and a walk by cursor skips none and repeats none`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val paid = t.plusSeconds(10)
        insert(
            store,
            orderAt(1).copy(paidAt = paid),
            orderAt(2, accountId = "acc_2", status = PaymentOrderStatus.PARTIALLY_REFUNDED).copy(paidAt = paid),
            orderAt(3).copy(paidAt = paid, accountErasedAt = t.plusSeconds(500)),
            orderAt(4, status = PaymentOrderStatus.PARTIALLY_REFUNDED).copy(paidAt = paid),
            orderAt(5, accountId = "acc_2").copy(paidAt = paid),
            orderAt(6).copy(paidAt = paid, fulfilledAt = t.plusSeconds(11)),
            orderAt(7).copy(paidAt = t.plusSeconds(5_000)),
        )
        val threshold = t.plusSeconds(1_000)

        fun ids(filter: OperatorOrderFilter = OperatorOrderFilter(), before: Int? = null, limit: Int = 10) =
            store.listOrdersForOperator(OperatorOrderScope.UNFULFILLED, filter, threshold, before?.let { OrderCursor(orderAt(it).createdAt, orderAt(it).id) }, limit).map { it.id.takeLast(2).toInt() }

        assertEquals(listOf(5, 4, 3, 2, 1), ids())
        assertEquals(listOf(4, 3, 1), ids(OperatorOrderFilter(accountId = "acc_1")))
        assertEquals(listOf(5, 2), ids(OperatorOrderFilter(accountId = "acc_2")))
        assertEquals(listOf(5, 4, 2, 1), ids(OperatorOrderFilter(hideErased = true)))
        assertEquals(listOf(4, 2), ids(OperatorOrderFilter(status = PaymentOrderStatus.PARTIALLY_REFUNDED)))
        assertEquals(listOf(5, 3, 1), ids(OperatorOrderFilter(status = PaymentOrderStatus.PAID)))
        assertEquals(listOf(4, 1), ids(OperatorOrderFilter(accountId = "acc_1", hideErased = true)))
        assertEquals(listOf(5), ids(limit = 1))
        assertEquals(listOf(3, 2), ids(before = 4, limit = 2))
        assertEquals(listOf(3), ids(OperatorOrderFilter(accountId = "acc_1"), before = 4, limit = 1))
        assertEquals(listOf(2), ids(OperatorOrderFilter(hideErased = true), before = 4, limit = 1))
        assertEquals(emptyList(), ids(before = 1))

        // 한 건씩 커서로 끝까지 — 건너뜀도 중복도 없이 전체와 같다
        val walked = mutableListOf<Int>()
        var cursor: Int? = null
        while (true) {
            val page = ids(before = cursor, limit = 1)
            if (page.isEmpty()) break
            walked += page
            cursor = page.single()
        }
        assertEquals(listOf(5, 4, 3, 2, 1), walked)
    }

    @Test
    fun `orders are found by provider payment id only among the given providers - and by id list, newest first`() {
        val store = newStore()
        insert(store, orderAt(1, provider = "scripted"), orderAt(2, provider = "other"), orderAt(3, provider = "scripted"))
        store.inTransaction { tx -> tx.lockOrder(orderAt(1).id); tx.updateOrder(orderAt(1).copy(providerPaymentId = "pay_same")) }
        store.inTransaction { tx -> tx.lockOrder(orderAt(2).id); tx.updateOrder(orderAt(2, provider = "other").copy(providerPaymentId = "pay_same")) }

        assertEquals(listOf(orderAt(1).id), store.findOrdersByProviderPayment(listOf("scripted"), "pay_same").map { it.id })
        assertEquals(setOf(orderAt(1).id, orderAt(2).id), store.findOrdersByProviderPayment(listOf("scripted", "other"), "pay_same").map { it.id }.toSet())
        assertEquals(emptyList(), store.findOrdersByProviderPayment(listOf("scripted"), "pay_none"))
        assertEquals(emptyList(), store.findOrdersByProviderPayment(emptyList(), "pay_same"))
        assertEquals(listOf(3, 1), store.listOrdersByIds(listOf(orderAt(1).id, orderAt(3).id, "ord_missing")).map { it.id.takeLast(2).toInt() })
        assertEquals(emptyList(), store.listOrdersByIds(emptyList()))
    }

    @Test
    fun `the refund lines of several orders come in one read - oldest first within an order`() {
        val store = newStore()
        insert(store, orderAt(1), orderAt(2), orderAt(3))
        val t = PaymentTestOrders.T0
        fun line(n: Int, order: PaymentOrder, status: PaymentRefundStatus, at: Instant) =
            PaymentTestOrders.refund(id = "rfd_" + n.toString().padStart(32, '0'), orderId = order.id, status = status, now = at)
        insertRefund(store, line(1, orderAt(1), PaymentRefundStatus.FAILED, t.plusSeconds(10)))
        insertRefund(store, line(2, orderAt(1), PaymentRefundStatus.REQUESTED, t.plusSeconds(20)))
        insertRefund(store, line(3, orderAt(2), PaymentRefundStatus.PENDING, t.plusSeconds(5)))

        val lines = store.listRefundsOfOrders(listOf(orderAt(1).id, orderAt(2).id))

        assertEquals(listOf(1, 2), lines.filter { it.orderId == orderAt(1).id }.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(3), lines.filter { it.orderId == orderAt(2).id }.map { it.id.takeLast(2).toInt() })
        assertEquals(3, lines.size)
        assertEquals(emptyList(), store.listRefundsOfOrders(emptyList()))
    }

    @Test
    fun `refund lines by status come newest first by the request time and page by a cursor`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        insert(store, orderAt(1), orderAt(2), orderAt(3), orderAt(4))
        fun line(n: Int, status: PaymentRefundStatus, at: Instant) =
            PaymentTestOrders.refund(id = "rfd_" + n.toString().padStart(32, '0'), orderId = orderAt(n).id, status = status, now = at)
        insertRefund(store, line(1, PaymentRefundStatus.REQUESTED, t.plusSeconds(10)))
        insertRefund(store, line(2, PaymentRefundStatus.REQUESTED, t.plusSeconds(30)))
        insertRefund(store, line(3, PaymentRefundStatus.PENDING, t.plusSeconds(20)))
        insertRefund(store, line(4, PaymentRefundStatus.REQUESTED, t.plusSeconds(30)))

        val firstPage = store.listRefundsByStatus(PaymentRefundStatus.REQUESTED, null, 2)
        val secondPage = store.listRefundsByStatus(PaymentRefundStatus.REQUESTED, RefundCursor(firstPage.last().requestedAt, firstPage.last().id), 2)

        assertEquals(listOf(4, 2), firstPage.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(1), secondPage.map { it.id.takeLast(2).toInt() })
        assertEquals(listOf(3), store.listRefundsByStatus(PaymentRefundStatus.PENDING, null, 10).map { it.id.takeLast(2).toInt() })
    }

    private fun refundLines(store: PaymentLedgerStore) {
        val t = PaymentTestOrders.T0
        insert(store, orderAt(1), orderAt(2), orderAt(3), orderAt(4), orderAt(5), orderAt(6, accountId = "acc_2").copy(accountErasedAt = t.plusSeconds(500)), orderAt(7, accountId = "acc_2"))
        fun line(n: Int, status: PaymentRefundStatus) = PaymentTestOrders.refund(id = "rfd_" + n.toString().padStart(32, '0'), orderId = orderAt(n).id, status = status, now = t.plusSeconds(n.toLong()))
        insertRefund(store, line(1, PaymentRefundStatus.PENDING).copy(sentAt = t.plusSeconds(10)))
        insertRefund(store, line(2, PaymentRefundStatus.PENDING).copy(sentAt = t.plusSeconds(5_000)))
        insertRefund(store, line(3, PaymentRefundStatus.SUCCEEDED).copy(completedAt = t.plusSeconds(10)))
        insertRefund(store, line(4, PaymentRefundStatus.SUCCEEDED).copy(completedAt = t.plusSeconds(10), appliedAt = t.plusSeconds(11)))
        insertRefund(store, line(5, PaymentRefundStatus.SUCCEEDED).copy(completedAt = t.plusSeconds(5_000)))
        insertRefund(store, line(6, PaymentRefundStatus.PENDING).copy(sentAt = t.plusSeconds(10)))
        insertRefund(store, line(7, PaymentRefundStatus.SUCCEEDED).copy(completedAt = null))
    }

    @Test
    fun `the stale-refund scope lists orders with a PENDING refund sent before the threshold, the unapplied-refund scope orders with a SUCCEEDED refund nobody applied - newest first`() {
        val store = newStore()
        refundLines(store)
        val threshold = PaymentTestOrders.T0.plusSeconds(1_000)

        fun ids(scope: OperatorOrderScope) = store.listOrdersForOperator(scope, OperatorOrderFilter(), threshold, null, 10).map { it.id.takeLast(2).toInt() }

        assertEquals(listOf(6, 1), ids(OperatorOrderScope.STALE_REFUND))
        assertEquals(listOf(7, 3), ids(OperatorOrderScope.UNAPPLIED_REFUND))
    }

    @Test
    fun `the refund scopes apply the account filter, the erased filter, the cursor and the limit to the orders - not to a capped read of the refund lines`() {
        val store = newStore()
        refundLines(store)
        val threshold = PaymentTestOrders.T0.plusSeconds(1_000)

        fun ids(scope: OperatorOrderScope, filter: OperatorOrderFilter = OperatorOrderFilter(), before: OrderCursor? = null, limit: Int = 10) =
            store.listOrdersForOperator(scope, filter, threshold, before, limit).map { it.id.takeLast(2).toInt() }

        assertEquals(listOf(6), ids(OperatorOrderScope.STALE_REFUND, OperatorOrderFilter(accountId = "acc_2")))
        assertEquals(listOf(1), ids(OperatorOrderScope.STALE_REFUND, OperatorOrderFilter(accountId = "acc_1")))
        assertEquals(listOf(1), ids(OperatorOrderScope.STALE_REFUND, OperatorOrderFilter(hideErased = true)))
        assertEquals(listOf(1), ids(OperatorOrderScope.STALE_REFUND, before = OrderCursor(orderAt(6).createdAt, orderAt(6).id)))
        assertEquals(listOf(7), ids(OperatorOrderScope.UNAPPLIED_REFUND, limit = 1))
        assertEquals(listOf(3), ids(OperatorOrderScope.UNAPPLIED_REFUND, before = OrderCursor(orderAt(7).createdAt, orderAt(7).id)))
        assertEquals(listOf(3), ids(OperatorOrderScope.UNAPPLIED_REFUND, OperatorOrderFilter(accountId = "acc_1", status = PaymentOrderStatus.PAID)))
        assertEquals(emptyList(), ids(OperatorOrderScope.UNAPPLIED_REFUND, OperatorOrderFilter(status = PaymentOrderStatus.PENDING)))
    }

    @Test
    fun `an order with two matching refund lines is listed once`() {
        val store = newStore()
        insert(store, orderAt(1))
        val t = PaymentTestOrders.T0
        insertRefund(store, PaymentTestOrders.refund(id = "rfd_" + "1".padStart(32, '0'), orderId = orderAt(1).id, status = PaymentRefundStatus.SUCCEEDED, now = t).copy(completedAt = t))
        insertRefund(store, PaymentTestOrders.refund(id = "rfd_" + "2".padStart(32, '0'), orderId = orderAt(1).id, status = PaymentRefundStatus.SUCCEEDED, now = t.plusSeconds(1)).copy(completedAt = t))

        assertEquals(1, store.listOrdersForOperator(OperatorOrderScope.UNAPPLIED_REFUND, OperatorOrderFilter(), t.plusSeconds(1_000), null, 10).size)
    }

    // --- 탈퇴 (벌크) ---

    @Test
    fun `an account's order ids come in ascending order and only its own`() {
        val store = newStore()
        insert(store, orderAt(3), orderAt(1), orderAt(2, accountId = "acc_other"), orderAt(4))

        assertEquals(listOf(1, 3, 4).map { orderAt(it).id }, store.listOrderIdsOfAccount("acc_1"))
        assertEquals(emptyList(), store.listOrderIdsOfAccount("acc_none"))
    }

    @Test
    fun `erasing an account clears the card on every one of its orders and stamps the time - nothing else changes, other accounts are untouched, and a second call changes nothing`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val withCard = orderAt(1).copy(cardIssuer = "HYUNDAI", cardLast4 = "1234", method = "CARD", providerPaymentId = "pay_1", paidAt = t.plusSeconds(2))
        val pending = orderAt(2, status = PaymentOrderStatus.PENDING)
        val otherAccount = orderAt(3, accountId = "acc_other").copy(cardIssuer = "KB", cardLast4 = "9999")
        insert(store, withCard, pending, otherAccount)
        val erasedAt = t.plusSeconds(1_000)

        assertEquals(2, store.eraseAccountOrders("acc_1", erasedAt))

        val cleared = store.findOrder(withCard.id)!!
        assertEquals(withCard.copy(cardIssuer = null, cardLast4 = null, accountErasedAt = erasedAt), cleared)
        assertEquals(pending.copy(accountErasedAt = erasedAt), store.findOrder(pending.id))
        assertEquals(otherAccount, store.findOrder(otherAccount.id))

        assertEquals(0, store.eraseAccountOrders("acc_1", erasedAt.plusSeconds(60)), "a second call finds nothing to change")
        assertEquals(cleared, store.findOrder(withCard.id), "the first erasure time stays")
    }

    @Test
    fun `erasing an account keeps an erasure time that is already there and still clears a card that came back`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val early = t.plusSeconds(100)
        insert(store, orderAt(1).copy(accountErasedAt = early, cardIssuer = "HYUNDAI", cardLast4 = "1234"))

        assertEquals(1, store.eraseAccountOrders("acc_1", t.plusSeconds(900)))

        val row = store.findOrder(orderAt(1).id)!!
        assertEquals(early, row.accountErasedAt)
        assertNull(row.cardIssuer)
        assertNull(row.cardLast4)
    }

    @Test
    fun `replacing an account's references changes the ids on its own orders only - an operator's id on someone else's order stays`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val own = orderAt(1)
        val others = orderAt(2, accountId = "acc_other")
        insert(store, own, others)
        // 그 계정이 자기 주문에 요청했고(requested_by = acc_1), 다른 사람의 주문에서는 운영자로 결정했다(decided_by = acc_1)
        insertRefund(store, PaymentTestOrders.refund(id = "rfd_" + "1".padStart(32, '0'), orderId = own.id, status = PaymentRefundStatus.REJECTED, now = t).copy(requestedBy = "acc_1", decidedBy = "operator_1"))
        insertRefund(store, PaymentTestOrders.refund(id = "rfd_" + "2".padStart(32, '0'), orderId = others.id, status = PaymentRefundStatus.REJECTED, now = t).copy(requestedBy = "acc_other", decidedBy = "acc_1"))
        store.inTransaction { tx ->
            tx.lockOrder(own.id)
            tx.appendEvent(PaymentEvent(own.id, PaymentEventType.ORDER_CREATED, PaymentActor.USER, t, actorId = "acc_1"))
            tx.appendEvent(PaymentEvent(own.id, PaymentEventType.REFUND_REJECTED, PaymentActor.OPERATOR, t, actorId = "operator_1"))
        }
        store.inTransaction { tx ->
            tx.lockOrder(others.id)
            tx.appendEvent(PaymentEvent(others.id, PaymentEventType.REFUND_REJECTED, PaymentActor.OPERATOR, t, actorId = "acc_1"))
        }

        val changed = store.replaceAccountReferences("acc_1", "deleted:abc")

        assertEquals(3, changed, "the order, its refund's requested_by and its event's actor_id")
        assertEquals("deleted:abc", store.findOrder(own.id)!!.accountId)
        assertEquals("deleted:abc", store.findRefund("rfd_" + "1".padStart(32, '0'))!!.requestedBy)
        assertEquals("operator_1", store.findRefund("rfd_" + "1".padStart(32, '0'))!!.decidedBy)
        assertEquals(listOf("deleted:abc", "operator_1"), store.listEvents(own.id).map { it.actorId })
        assertEquals("acc_other", store.findOrder(others.id)!!.accountId)
        assertEquals("acc_1", store.findRefund("rfd_" + "2".padStart(32, '0'))!!.decidedBy)
        assertEquals(listOf("acc_1"), store.listEvents(others.id).map { it.actorId })
        assertEquals(0, store.replaceAccountReferences("acc_1", "deleted:abc"), "a second call finds no order of acc_1 any more")
    }

    @Test
    fun `replacing references also replaces the operator ids an erased account wrote on its own orders when the operator was the account itself`() {
        val store = newStore()
        val t = PaymentTestOrders.T0
        val own = orderAt(1)
        insert(store, own)
        insertRefund(store, PaymentTestOrders.refund(id = "rfd_" + "1".padStart(32, '0'), orderId = own.id, status = PaymentRefundStatus.SUCCEEDED, now = t).copy(requestedBy = "acc_1", decidedBy = "acc_1"))

        store.replaceAccountReferences("acc_1", "deleted:abc")

        val line = store.findRefund("rfd_" + "1".padStart(32, '0'))!!
        assertEquals("deleted:abc", line.requestedBy)
        assertEquals("deleted:abc", line.decidedBy)
    }

    /** 모든 칸에 서로 다른 값을 넣은 줄 — 왕복에서 칸이 바뀌거나 빠지면 드러난다. 시각은 초 단위(저장소의 시각 정밀도에 기대지 않는다). */
    private fun fullOrder(): PaymentOrder {
        val t = PaymentTestOrders.T0
        return PaymentOrder(
            id = "ord_" + "3".repeat(32),
            accountId = "acc_full",
            kind = PaymentOrderKind.SUBSCRIPTION_INITIAL,
            status = PaymentOrderStatus.PAID,
            provider = "scripted",
            providerMode = PaymentMode.LIVE,
            providerPaymentId = "pay_full",
            amount = PaymentAmount(3_000, "KRW"),
            paidAmount = 3_000,
            taxAmount = 273,
            refundedAmount = 0,
            itemType = "plan",
            itemRef = "plan-pro",
            quantity = 2,
            orderName = "Plan Pro 2 months",
            originType = PaymentOriginType.SUBSCRIPTION,
            originRef = "sub_1",
            dedupeKey = "dedupe-full",
            method = "CARD",
            cardIssuer = "HYUNDAI",
            cardLast4 = "1234",
            failureCode = "SOME_CODE",
            reviewReason = PaymentReviewReason.AMOUNT_MISMATCH,
            checkoutExpiresAt = t.plusSeconds(1_800),
            confirmStartedAt = t.plusSeconds(11),
            paidAt = t.plusSeconds(12),
            fulfilledAt = t.plusSeconds(13),
            closedAt = t.plusSeconds(14),
            accountErasedAt = t.plusSeconds(15),
            createdAt = t.plusSeconds(1),
            updatedAt = t.plusSeconds(16),
        )
    }

    private fun event(orderId: String, type: PaymentEventType = PaymentEventType.ORDER_CREATED) =
        PaymentEvent(orderId = orderId, type = type, actor = PaymentActor.SYSTEM, createdAt = PaymentTestOrders.T0)

    // --- 구독: 계정당 살아 있는 구독 하나(자리 유니크) · 줄 락 · 모든 칸 왕복. 두 DB 와 메모리 대역이 같은 약속을 지킨다. ---

    private val sub1 = PaymentTestOrders.subscription(id = "sub_" + "1".repeat(32))
    private val sub2 = PaymentTestOrders.subscription(id = "sub_" + "2".repeat(32))

    @Test
    fun `a subscription round-trips every column and a repeated insert of the same id is refused`() {
        val store = newStore()
        val full = sub1.copy(
            status = SubscriptionStatus.ACTIVE, activeKey = "acc_1", anchorAt = PaymentTestOrders.T0, periodSeq = 3,
            currentPeriodStart = PaymentTestOrders.T0, currentPeriodEnd = PaymentTestOrders.T0.plusSeconds(3600), chargeOrderId = "ord_" + "9".repeat(32), attempt = 2,
            billingToken = "enc:secret", cardIssuer = "HYUNDAI", cardLast4 = "123*", pendingPlanCode = "plus", pendingAmount = 6900,
            endReason = SubscriptionEndReason.PAYMENT_FAILED, endedAt = PaymentTestOrders.T0.plusSeconds(5), accountErasedAt = PaymentTestOrders.T0.plusSeconds(6),
        )

        assertTrue(store.inTransaction { it.insertSubscriptionIfAbsent(full) })
        assertFalse(store.inTransaction { it.insertSubscriptionIfAbsent(full.copy(customerKey = "cus_" + "f".repeat(32))) }, "the same request id is the same subscription")

        assertEquals(full, store.findSubscription(sub1.id))
        assertEquals(full, store.findLiveSubscription("acc_1"))
        assertNull(store.findSubscription("sub_missing"))
    }

    @Test
    fun `an account holds one live subscription - the second seat is refused and the seat comes back when the first ends`() {
        val store = newStore()
        store.inTransaction { it.insertSubscriptionIfAbsent(sub1) }
        store.inTransaction { it.insertSubscriptionIfAbsent(sub2) }
        store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(sub1.id)).copy(activeKey = "acc_1")) }

        assertFailsWith<PaymentLedgerConflictException> {
            store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(sub2.id)).copy(activeKey = "acc_1")) }
        }
        assertNull(checkNotNull(store.findSubscription(sub2.id)).activeKey, "the refused claim is rolled back")

        store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(sub1.id)).copy(status = SubscriptionStatus.ENDED, activeKey = null)) }
        store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(sub2.id)).copy(activeKey = "acc_1")) }
        assertEquals(sub2.id, store.findLiveSubscription("acc_1")?.id)
    }

    @Test
    fun `sixteen threads claiming the seat of one account - exactly one wins`() {
        val store = newStore()
        val ids = (1..16).map { n -> "sub_" + n.toString(16).padStart(32, '0') }
        ids.forEach { id -> store.inTransaction { it.insertSubscriptionIfAbsent(PaymentTestOrders.subscription(id = id)) } }
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val results = ids.map { id ->
                pool.submit<Boolean> {
                    start.await()
                    try {
                        store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(id)).copy(activeKey = "acc_1")) }
                        true
                    } catch (e: PaymentLedgerConflictException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        assertNotNull(store.findLiveSubscription("acc_1"))
    }

    @Test
    fun `a subscription row lock makes sixteen read-modify-write loops count exactly`() {
        val store = newStore()
        store.inTransaction { it.insertSubscriptionIfAbsent(sub1) }
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val futures = (1..16).map {
                pool.submit {
                    start.await()
                    store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(sub1.id)).let { it.copy(periodSeq = it.periodSeq + 1) }) }
                }
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(16, store.findSubscription(sub1.id)?.periodSeq)
    }

    @Test
    fun `a subscription can only be changed after it was locked and the order is locked before the subscription`() {
        val store = newStore()
        store.inTransaction { it.insertSubscriptionIfAbsent(sub1) }
        store.inTransaction { it.insertOrderIfAbsent(first) }

        assertFailsWith<IllegalStateException> { store.inTransaction { it.updateSubscription(sub1.copy(attempt = 2)) } }
        assertFailsWith<IllegalStateException> { store.inTransaction { tx -> tx.lockSubscription(sub1.id); tx.lockSubscription(sub2.id) } }
        // 주문 → 구독 순서는 된다 (지급 고리)
        store.inTransaction { tx ->
            checkNotNull(tx.lockOrder(first.id))
            tx.updateSubscription(checkNotNull(tx.lockSubscription(sub1.id)).copy(attempt = 2))
        }
        assertEquals(2, store.findSubscription(sub1.id)?.attempt)
    }

    @Test
    fun `the latest subscription skips one that was only created, and the subscription orders are the paid attempts newest first`() {
        val store = newStore()
        store.inTransaction { it.insertSubscriptionIfAbsent(sub1.copy(createdAt = PaymentTestOrders.T0)) }
        assertNull(store.findLatestSubscription("acc_1"), "a PENDING subscription that never took a card is not shown")

        store.inTransaction { tx -> tx.updateSubscription(checkNotNull(tx.lockSubscription(sub1.id)).copy(chargeOrderId = "ord_" + "9".repeat(32), activeKey = "acc_1")) }
        store.inTransaction { it.insertSubscriptionIfAbsent(sub2.copy(createdAt = PaymentTestOrders.T0.plusSeconds(10))) }
        assertEquals(sub1.id, store.findLatestSubscription("acc_1")?.id)

        val o1 = first.copy(id = "ord_" + "1".repeat(32), dedupeKey = "d1", originType = PaymentOriginType.SUBSCRIPTION, originRef = sub1.id, status = PaymentOrderStatus.PAID, createdAt = PaymentTestOrders.T0)
        val o2 = o1.copy(id = "ord_" + "2".repeat(32), dedupeKey = "d2", createdAt = PaymentTestOrders.T0.plusSeconds(5))
        val pending = o1.copy(id = "ord_" + "3".repeat(32), dedupeKey = "d3", status = PaymentOrderStatus.PENDING, createdAt = PaymentTestOrders.T0.plusSeconds(6))
        val other = o1.copy(id = "ord_" + "4".repeat(32), dedupeKey = "d4", originRef = sub2.id)
        listOf(o1, o2, pending, other).forEach { order -> store.inTransaction { it.insertOrderIfAbsent(order) } }

        assertEquals(listOf(o2.id, o1.id), store.listSubscriptionOrders(sub1.id, 5).map { it.id })
        assertEquals(listOf(o2.id), store.listSubscriptionOrders(sub1.id, 1).map { it.id })
        assertEquals(listOf(sub1.id, sub2.id).sorted(), store.listSubscriptionIdsOfAccount("acc_1"))
    }

    @Test
    fun `a tombstone replaces the account id of the subscription lines too, and a second call finds nothing`() {
        val store = newStore()
        store.inTransaction { it.insertSubscriptionIfAbsent(sub1) }
        store.inTransaction { it.insertSubscriptionIfAbsent(sub2.copy(accountId = "acc_other")) }

        assertTrue(store.replaceAccountReferences("acc_1", "deleted:abc") >= 1)

        assertEquals("deleted:abc", store.findSubscription(sub1.id)?.accountId)
        assertEquals("acc_other", store.findSubscription(sub2.id)?.accountId, "somebody else's line is not touched")
        assertEquals(0, store.replaceAccountReferences("acc_1", "deleted:abc"))
    }
}
