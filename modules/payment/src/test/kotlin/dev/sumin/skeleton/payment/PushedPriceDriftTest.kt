package dev.sumin.skeleton.payment

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

/**
 * 결제사(Paddle)가 확정한 금액은 그대로 기록하고, 같은 통화인데 세전 금액이 우리 DB 가격과 다르면 경고 한 줄만 낸다 - 지급 · 기록은 그대로.
 * 통화가 다르면(구매자 통화로 환산) 비교하지 않고, 갱신 결제도 비교하지 않는다.
 */
class PushedPriceDriftTest {
    private val push = FakePushGateway()
    private val kit = PaymentLedgerKit(push = push)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private var seq = 0
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(PaymentPushedEvents::class.java) as Logger

    @BeforeTest
    fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun release() {
        logger.detachAppender(appender)
    }

    private fun drifts(): List<String> = appender.list.filter { it.level == Level.WARN && it.formattedMessage.startsWith("payment.price-drift") }.map { it.formattedMessage }

    private fun payment(txn: String?, total: PaymentAmount, tax: Long = 90, order: String? = null, at: Instant = t0, providerSub: String? = "psub_1") =
        PushedPayment(
            eventId = "evt_${++seq}", occurredAt = at, providerPaymentId = txn ?: "txn_unknown", total = total, tax = tax, paidAt = at, ourOrderId = order, ourAccountId = "acc_1",
            providerSubscriptionId = providerSub, periodStart = at, periodEnd = at.plusSeconds(31 * 86400),
            items = listOf(PushedItem(if (order != null) "pri_cheer-3000" else "pri_basic", 1)),
        )

    private fun feed(event: PushedPaymentEvent) {
        kit.deliver(push.delivery(event), token = null, provider = "paddle")
        kit.runWebhooks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
    }

    @Test
    fun `a one-time payment whose pre-tax amount differs in the same currency warns once and is still paid and fulfilled`() {
        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")).order
        val txn = checkNotNull(kit.order(order.id).providerPaymentId)

        feed(payment(txn, PaymentAmount(3_590, "KRW"), tax = 90, order = order.id)) // 세전 3,500 != 3,000

        val warned = drifts().single()
        assertTrue(order.id in warned && "cheer-3000" in warned && "3000" in warned && "3500" in warned, warned)
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
        assertEquals(3_590, kit.order(order.id).amount.amount, "the provider's figure is what is recorded")
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `the same pre-tax amount and a different currency do not warn`() {
        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")).order
        val txn = checkNotNull(kit.order(order.id).providerPaymentId)
        feed(payment(txn, PaymentAmount(3_090, "KRW"), tax = 90, order = order.id))
        assertEquals(emptyList(), drifts(), "3,090 - 90 = 3,000 is the price")

        val other = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o2"), provider = "paddle")).order
        feed(payment(checkNotNull(kit.order(other.id).providerPaymentId), PaymentAmount(2_500, "USD"), tax = 90, order = other.id))
        assertEquals(emptyList(), drifts(), "converted to the buyer's currency - nothing to compare")
        assertEquals(PaymentOrderStatus.PAID, kit.order(other.id).status)
    }

    @Test
    fun `the first payment of a subscription is compared with the stored plan amount, a renewal is not`() {
        val pending = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "paddle").subscription
        feed(payment(pending.firstTransactionId, PaymentAmount(4_590, "KRW"), tax = 90)) // 세전 4,500 != 3,900
        assertTrue("basic" in drifts().single() && "3900" in drifts().single() && "4500" in drifts().single(), drifts().toString())
        assertEquals(SubscriptionStatus.ACTIVE, checkNotNull(kit.store.findSubscription(pending.id)).status)

        feed(payment("txn_renewal_1", PaymentAmount(9_990, "KRW"), tax = 90, at = t0.plusSeconds(31 * 86400)))
        assertEquals(1, drifts().size, "the renewal is not compared")
        assertEquals(2, kit.store.listSubscriptionOrders(pending.id, 5).size)
    }
}
