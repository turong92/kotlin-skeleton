package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderCreateTest {
    @Test
    fun `an order takes its amount, name and mode from the catalog and the provider - never from the client`() {
        val kit = PaymentLedgerKit()

        val created = kit.create(accountId = "acc_1", itemRef = "cheer-3000", key = "key-1")
        val order = created.order

        assertTrue(Regex("ord_[0-9a-hjkmnp-tv-z]{26}").matches(order.id), order.id)
        assertEquals(PaymentOrderStatus.PENDING, order.status)
        assertEquals(PaymentAmount(3000, "KRW"), order.amount)
        assertEquals("Cheer 3000", order.orderName)
        assertEquals(PaymentMode.TEST, order.providerMode)
        assertEquals(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("key-1")), order.dedupeKey)
        assertEquals(kit.time.now().plus(Duration.ofMinutes(30)), order.checkoutExpiresAt)
        assertFalse(created.replayed)
        assertEquals(order, kit.store.findOrder(order.id))
    }

    @Test
    fun `the same key with the same body returns the same order, with a different body it conflicts, from another account it is another order`() {
        val kit = PaymentLedgerKit()

        val first = kit.create(key = "k")
        val second = kit.create(key = "k")

        assertEquals(first.order.id, second.order.id)
        assertTrue(second.replayed)
        assertEquals(1, kit.store.allOrders().size)
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).size)
        assertFailsWith<PaymentIdempotencyConflictException> { kit.create(key = "k", itemRef = "cheer-5000") }
        assertTrue(kit.create(accountId = "acc_2", key = "k").order.id != first.order.id)
        assertEquals(2, kit.store.allOrders().size)
    }

    @Test
    fun `a replay after the order was paid still returns the order and no checkout`() {
        val kit = PaymentLedgerKit()
        val order = kit.createAuthenticated().also { kit.orders.confirm("acc_1", it.id, "pay_1", it.amount) }

        val replay = kit.create(key = "key-1")

        assertEquals(order.id, replay.order.id)
        assertEquals(PaymentOrderStatus.PAID, replay.order.status)
        assertNull(replay.checkout)
    }

    @Test
    fun `sixteen simultaneous creates with one key make one order and everyone gets it`() {
        val kit = PaymentLedgerKit()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)
        val ids = try {
            val futures = (1..16).map { pool.submit<String> { start.await(); kit.create(key = "same").order.id } }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, ids.toSet().size)
        assertEquals(1, kit.store.allOrders().size)
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).size)
    }

    @Test
    fun `no catalog, a refused item, a zero quote or a provider without checkout values creates no order and no task`() {
        val noCatalog = PaymentLedgerKit(catalog = null)
        val normal = PaymentLedgerKit()
        val zero = PaymentLedgerKit(catalog = PaymentCatalog { PurchaseQuote(PaymentAmount(0, "KRW"), "Free") })
        val notReady = PaymentLedgerKit().also { it.gateway.checkoutReady = false }

        assertFailsWith<PaymentUnavailableException> { noCatalog.create() }
        assertFailsWith<PaymentItemNotPurchasableException> { normal.create(itemRef = "cheer-999999") }
        assertFailsWith<PaymentItemNotPurchasableException> { normal.create(quantity = 2) }
        assertFailsWith<PaymentItemNotPurchasableException> { zero.create() }
        assertFailsWith<PaymentUnavailableException> { notReady.create(key = "k") }

        listOf(noCatalog, normal, zero, notReady).forEach { assertEquals(0, it.store.allOrders().size) ; assertEquals(0, it.tasks.all().size) }
    }

    @Test
    fun `if the task cannot be queued the order is not written either`() {
        val kit = PaymentLedgerKit()
        val orders = PaymentOrders(kit.store, PaymentTasks { error("queue down") }, kit.router, kit.catalog, kit.convergence, kit.fulfillment, kit.writer, kit.properties)

        assertFailsWith<IllegalStateException> { orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k"))) }
        assertEquals(0, kit.store.allOrders().size)
    }
}
