package dev.sumin.skeleton.payment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import org.slf4j.LoggerFactory
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
    fun `creating an order writes every column of the ledger row from the catalog and the chosen provider`() {
        val kit = PaymentLedgerKit()

        val created = kit.create(accountId = "acc_1", itemRef = "cheer-3000", key = "key-1")
        val order = created.order

        assertTrue(Regex("ord_[0-9a-f]{32}").matches(order.id), order.id)
        assertEquals("acc_1", order.accountId)
        assertEquals(PaymentOrderKind.ONE_TIME, order.kind)
        assertEquals(PaymentOrderStatus.PENDING, order.status)
        assertEquals("scripted", order.provider)
        assertEquals(PaymentMode.TEST, order.providerMode)
        assertNull(order.providerPaymentId)
        assertEquals(PaymentAmount(3000, "KRW"), order.amount)
        assertNull(order.paidAmount)
        assertEquals(0, order.refundedAmount)
        assertEquals("cheer", order.itemType)
        assertEquals("cheer-3000", order.itemRef)
        assertEquals(1, order.quantity)
        assertEquals("Cheer 3000", order.orderName)
        assertEquals(PaymentOriginType.CHECKOUT, order.originType)
        assertNull(order.originRef)
        assertEquals(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("key-1")), order.dedupeKey)
        assertNull(order.cardIssuer)
        assertNull(order.cardLast4)
        assertNull(order.reviewReason)
        assertEquals(kit.time.now(), order.createdAt)
        assertEquals(kit.time.now(), order.updatedAt)
        assertEquals(kit.time.now().plus(Duration.ofMinutes(30)), order.checkoutExpiresAt)
        assertFalse(created.replayed)
        assertEquals(order, kit.store.findOrder(order.id))
    }

    @Test
    fun `the checkout instruction comes from the provider and names this order and its amount`() {
        val kit = PaymentLedgerKit()

        val created = kit.create()
        val checkout = assertNotNull(created.checkout)

        assertEquals(created.order.id, checkout.orderId)
        assertEquals(PaymentAmount(3000, "KRW"), checkout.amount)
        assertEquals("scripted", checkout.provider)
        assertEquals(1, kit.gateway.checkoutCalls.size)
    }

    @Test
    fun `creating queues an expiry task at the checkout deadline in the same transaction and writes the first event`() {
        val kit = PaymentLedgerKit()

        val order = kit.create().order

        val task = kit.tasks.pending(PaymentTaskTypes.ORDER_EXPIRE).single()
        assertEquals(order.id, task.task.subjectId)
        assertEquals(order.checkoutExpiresAt, task.task.runAt)
        assertEquals(12, task.maxAttempts)
        assertEquals(listOf(PaymentEventType.ORDER_CREATED), kit.store.listEvents(order.id).map { it.type })
        assertEquals(PaymentActor.USER, kit.store.listEvents(order.id).single().actor)
        assertEquals("acc_1", kit.store.listEvents(order.id).single().actorId)
    }

    @Test
    fun `the same key with the same body returns the same order and creates nothing new`() {
        val kit = PaymentLedgerKit()

        val first = kit.create(key = "k")
        val second = kit.create(key = "k")

        assertEquals(first.order.id, second.order.id)
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(1, kit.store.allOrders().size)
        assertEquals(1, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).size)
        assertEquals(1, kit.store.listEvents(first.order.id).size)
    }

    @Test
    fun `the same key with a different body is an idempotency conflict`() {
        val kit = PaymentLedgerKit()
        kit.create(key = "k", itemRef = "cheer-3000")

        assertFailsWith<PaymentIdempotencyConflictException> { kit.create(key = "k", itemRef = "cheer-5000") }
        assertEquals(1, kit.store.allOrders().size)
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
    fun `the same key from another account creates another order`() {
        val kit = PaymentLedgerKit()

        val mine = kit.create(accountId = "acc_1", key = "k").order
        val theirs = kit.create(accountId = "acc_2", key = "k").order

        assertTrue(mine.id != theirs.id)
        assertEquals(2, kit.store.allOrders().size)
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
    fun `without a catalog an order cannot be created`() {
        val kit = PaymentLedgerKit(catalog = null)

        assertFailsWith<PaymentUnavailableException> { kit.create() }
        assertEquals(0, kit.store.allOrders().size)
    }

    @Test
    fun `an item the catalog refuses creates no order and no task`() {
        val kit = PaymentLedgerKit()

        assertFailsWith<PaymentItemNotPurchasableException> { kit.create(itemRef = "cheer-999999") }
        assertFailsWith<PaymentItemNotPurchasableException> { kit.create(quantity = 2) }
        assertEquals(0, kit.store.allOrders().size)
        assertEquals(0, kit.tasks.all().size)
    }

    @Test
    fun `a quote of zero or a blank order name is refused before anything is written`() {
        val zero = PaymentLedgerKit(catalog = PaymentCatalog { PurchaseQuote(PaymentAmount(0, "KRW"), "Free") })
        val blank = PaymentLedgerKit(catalog = PaymentCatalog { PurchaseQuote(PaymentAmount(10, "KRW"), "  ") })

        assertFailsWith<PaymentItemNotPurchasableException> { zero.create() }
        assertFailsWith<IllegalStateException> { blank.create() }
        assertEquals(0, zero.store.allOrders().size + blank.store.allOrders().size)
    }

    @Test
    fun `an order name longer than a hundred characters is cut to the provider limit`() {
        val kit = PaymentLedgerKit(catalog = PaymentCatalog { PurchaseQuote(PaymentAmount(10, "KRW"), "x".repeat(150)) })

        assertEquals(100, kit.create().order.orderName.length)
    }

    @Test
    fun `when no provider can open a checkout the answer is unavailable`() {
        val kit = PaymentLedgerKit()
        val router = PaymentProviderRouter(emptyList(), PaymentProperties())
        val orders = PaymentOrders(kit.store, kit.tasks, router, kit.catalog, kit.convergence, kit.fulfillment, kit.writer, kit.properties)

        assertFailsWith<PaymentUnavailableException> {
            orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k")))
        }
    }

    @Test
    fun `a provider that cannot hand out its checkout values makes the new order unavailable - no order, no task, no event`() {
        val kit = PaymentLedgerKit()
        kit.gateway.checkoutReady = false

        assertFailsWith<PaymentUnavailableException> { kit.create(key = "k") }

        assertEquals(0, kit.store.allOrders().size)
        assertEquals(0, kit.tasks.all(PaymentTaskTypes.ORDER_EXPIRE).size)
    }

    @Test
    fun `the same key again for a payable order is unavailable too when the provider can no longer hand out its checkout values - the order itself stays`() {
        val kit = PaymentLedgerKit()
        val first = kit.create(key = "k")
        kit.gateway.checkoutReady = false

        assertFailsWith<PaymentUnavailableException> { kit.create(key = "k") }

        assertEquals(PaymentOrderStatus.PENDING, kit.store.findOrder(first.order.id)!!.status)
        kit.gateway.checkoutReady = true
        assertNotNull(kit.create(key = "k").checkout)
    }

    @Test
    fun `the config answer and order creation choose the provider the same way`() {
        val kit = PaymentLedgerKit()
        assertNotNull(kit.orders.openableCheckout(null, null))
        assertNotNull(kit.orders.openableCheckout(PaymentAmount(3000, "KRW"), "KR"))
        kit.gateway.checkoutReady = false
        assertNull(kit.orders.openableCheckout(null, null))
        assertNull(kit.orders.openableCheckout(PaymentAmount(3000, "KRW"), "KR"))
    }

    @Test
    fun `asking whether a checkout can open is silent - only creating an order says why it could not`() {
        val kit = PaymentLedgerKit()
        kit.gateway.checkoutReady = false
        val logger = LoggerFactory.getLogger(PaymentOrders::class.java) as Logger
        val previous = logger.level
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            repeat(3) { assertNull(kit.orders.openableCheckout(null, null)) } // GET /config has no rate limit - it must not fill the log
            assertEquals(emptyList(), appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage })

            assertFailsWith<PaymentUnavailableException> { kit.create(key = "k-warn") }
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
        val warnings = appender.list.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size, warnings.map { it.formattedMessage }.toString())
        assertTrue(warnings.single().formattedMessage.contains("cannot hand out its checkout values"), warnings.single().formattedMessage)
    }

    @Test
    fun `if the task cannot be queued the order is not written either`() {
        val kit = PaymentLedgerKit()
        val orders = PaymentOrders(
            kit.store,
            PaymentTasks { error("queue down") },
            kit.router,
            kit.catalog,
            kit.convergence,
            kit.fulfillment,
            kit.writer,
            kit.properties,
        )

        assertFailsWith<IllegalStateException> { orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("k"))) }
        assertEquals(0, kit.store.allOrders().size)
    }

    @Test
    fun `the exception for a missing catalog carries no provider or type names`() {
        val message = PaymentUnavailableException().message.orEmpty()

        listOf("scripted", "Gateway", "dev.sumin", "toss").forEach { assertFalse(message.contains(it), message) }
    }
}
