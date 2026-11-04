package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentFulfillment
import dev.sumin.skeleton.payment.PaymentMode
import dev.sumin.skeleton.payment.PaymentOrderStatus
import dev.sumin.skeleton.payment.PaymentProperties
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentTaskRetryException
import dev.sumin.skeleton.payment.PaymentTaskRun
import dev.sumin.skeleton.payment.PaymentTaskTypes
import dev.sumin.skeleton.payment.PaymentWebhookStatus
import dev.sumin.skeleton.payment.PaymentWebhookTaskHandler
import dev.sumin.skeleton.payment.PushedPayment
import dev.sumin.skeleton.payment.PaymentPushedEvents
import dev.sumin.skeleton.payment.PaymentReviewReason
import dev.sumin.skeleton.payment.PaymentStorePurchases
import dev.sumin.skeleton.payment.PushedSubscriptionStatus
import dev.sumin.skeleton.payment.StoreAccountTokens
import dev.sumin.skeleton.payment.StoreProduct
import dev.sumin.skeleton.payment.StorePurchase
import dev.sumin.skeleton.payment.StorePurchaseKind
import dev.sumin.skeleton.payment.StorePurchaseState
import dev.sumin.skeleton.payment.StoreRecordOutcome
import dev.sumin.skeleton.payment.StoreRecordResult
import dev.sumin.skeleton.payment.SubscriptionEndReason
import dev.sumin.skeleton.payment.SubscriptionLifecycle
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionStatus
import dev.sumin.skeleton.payment.testing.FakeStoreGateway
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 스토어 구매 장부 기록(인앱결제 K2) - 진짜 DB · 진짜 락(두 방언)에서: 같은 구매는 순서대로 두 번 · 열여섯 번 동시에 와도 주문 한 줄 · 지급 한 번이고,
 * 다른 계정은 구매를 가져가지 못하고, 자리가 겹치면 돈만 장부에 남는다.
 */
class StorePurchasesDbTest {
    private val gateway = FakeStoreGateway()
    private val t0: Instant = Instant.parse("2026-11-04T10:00:00Z")
    private val monthLater: Instant = Instant.parse("2026-12-04T10:00:00Z")
    private val cheer = StoreProduct.OneTime("cheer", "cheer-3000", "Cheer 3000")
    private val basic = StoreProduct.Subscription(SubscriptionPlan("basic", "Basic", PaymentAmount(3_900, "KRW"), "P1M", 1))

    private lateinit var kit: JdbcLedgerKit
    private lateinit var purchases: PaymentStorePurchases
    private lateinit var pushedEvents: PaymentPushedEvents

    @BeforeTest
    fun setUp() {
        PaymentDb.clean()
        kit = JdbcLedgerKit()
        val lifecycle = SubscriptionLifecycle(kit.tasks, kit.writer, PaymentProperties(subscription = PaymentProperties.Subscription(enabled = true)), kit.review)
        val fulfillment = PaymentFulfillment(kit.store, kit.listener, kit.writer, lifecycle)
        val pushed = PaymentPushedEvents(kit.store, kit.tasks, kit.writer, lifecycle, kit.review)
        pushedEvents = pushed
        purchases = PaymentStorePurchases(kit.store, kit.tasks, kit.writer, pushed, lifecycle, kit.review, fulfillment)
    }

    private fun oneTime(txn: String = "txn_1", token: String? = StoreAccountTokens.of("acc_1")) = StorePurchase(
        mode = PaymentMode.TEST, kind = StorePurchaseKind.ONE_TIME, productId = "cheer_3000", providerPaymentId = txn, accountToken = token,
        amount = PaymentAmount(3_000, "KRW"), paidAt = t0, state = StorePurchaseState.PURCHASED,
    )

    private fun subscription(txn: String = "txn_1", sub: String = "orig_1", token: String? = StoreAccountTokens.of("acc_1"), replaces: String? = null) = StorePurchase(
        mode = PaymentMode.LIVE, kind = StorePurchaseKind.SUBSCRIPTION, productId = "premium", basePlanId = "monthly", providerPaymentId = txn, providerSubscriptionId = sub,
        replacesSubscriptionId = replaces, accountToken = token, amount = PaymentAmount(3_900, "KRW"), paidAt = t0, periodStart = t0, periodEnd = monthLater,
        state = StorePurchaseState.PURCHASED, subscriptionStatus = PushedSubscriptionStatus.ACTIVE,
    )

    /** the fixture ledger clock sits before the fixed purchase times, so the "asked" time is pinned after them. */
    private fun record(purchase: StorePurchase, product: StoreProduct, account: String = "acc_1") = purchases.record(gateway, account, purchase, product, monthLater.plusSeconds(1))

    private fun orderRows() = PaymentDb.count("select count(*) from payment_orders where provider = 'appstore'")

    private fun subscriptionRows() = PaymentDb.count("select count(*) from payment_subscriptions where provider = 'appstore'")

    private fun all(results: List<Result<StoreRecordResult>>): List<StoreRecordResult> {
        assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        return results.map { it.getOrThrow() }
    }

    @Test
    fun `the same one-time purchase twice in a row and then sixteen at once leaves one order and one grant`() {
        assertEquals(StoreRecordOutcome.RECORDED, record(oneTime(), cheer).outcome)
        assertEquals(StoreRecordOutcome.ALREADY_RECORDED, record(oneTime(), cheer).outcome)

        val results = all(race(16) { record(oneTime(), cheer) })

        assertTrue(results.all { it.outcome == StoreRecordOutcome.ALREADY_RECORDED })
        assertEquals(1, orderRows())
        assertEquals(1, kit.listener.paid.size)
        val order = checkNotNull(kit.store.findOrderByProviderPayment("appstore", "txn_1"))
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(PaymentMode.TEST, order.providerMode)
        assertNotNull(order.fulfilledAt)
    }

    @Test
    fun `sixteen threads confirming a new one-time purchase at once record it once and grant it once`() {
        val results = all(race(16) { record(oneTime(), cheer) })

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(15, results.count { it.outcome == StoreRecordOutcome.ALREADY_RECORDED })
        assertEquals(1, orderRows())
        assertEquals(1, kit.listener.paid.size)
        assertEquals(16, gateway.acknowledged.size, "every confirmation tells the store - once new, fifteen times already recorded")
    }

    @Test
    fun `a token of another account is refused and writes nothing - a purchase without a token goes to whoever confirmed it first`() {
        assertEquals(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT, record(oneTime(token = StoreAccountTokens.of("acc_1")), cheer, account = "acc_2").outcome)
        assertEquals(0, orderRows())

        val tokenless = oneTime(txn = "txn_2", token = null)
        val results = all(race(16) { n -> record(tokenless, cheer, account = if (n % 2 == 0) "acc_1" else "acc_2") })

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(7, results.count { it.outcome == StoreRecordOutcome.ALREADY_RECORDED }, "the winner's account asking again")
        assertEquals(8, results.count { it.outcome == StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT }, "the other account")
        assertEquals(1, orderRows())
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `the same first subscription twice and sixteen at once leaves one line one order and one grant`() {
        assertEquals(StoreRecordOutcome.RECORDED, record(subscription(), basic).outcome)
        assertEquals(StoreRecordOutcome.ALREADY_RECORDED, record(subscription(), basic).outcome)

        val results = all(race(16) { record(subscription(), basic) })

        assertTrue(results.all { it.outcome == StoreRecordOutcome.ALREADY_RECORDED })
        assertEquals(1, subscriptionRows())
        assertEquals(1, orderRows())
        assertEquals(1, kit.listener.paid.size)
        val line = checkNotNull(kit.store.findLiveSubscription("acc_1"))
        assertEquals(SubscriptionStatus.ACTIVE, line.status)
        assertEquals("orig_1", line.providerSubscriptionId)
    }

    @Test
    fun `sixteen threads confirming a new subscription at once record it once`() {
        val results = all(race(16) { record(subscription(), basic) })

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(1, subscriptionRows())
        assertEquals(1, orderRows())
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `two different subscriptions of one account racing - one takes the seat, the other keeps only its money flagged`() {
        val results = all(race(2) { n -> record(subscription(txn = "txn_$n", sub = "orig_$n"), basic) })

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.ALREADY_SUBSCRIBED })
        assertEquals(2, orderRows())
        assertEquals(1, kit.listener.paid.size, "only the line that holds the seat is granted")
        val flagged = results.single { it.outcome == StoreRecordOutcome.ALREADY_SUBSCRIBED }
        assertEquals(PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN, kit.order(checkNotNull(flagged.orderId)).reviewReason)
        assertEquals(results.single { it.outcome == StoreRecordOutcome.RECORDED }.subscriptionId, kit.store.findLiveSubscription("acc_1")?.id)
    }

    @Test
    fun `a subscription that expired and was bought again - sixteen confirmations at once - revives the same line once with one new order and one grant`() {
        val first = record(subscription(), basic)
        record(subscription().copy(subscriptionStatus = PushedSubscriptionStatus.ENDED), basic)
        assertEquals(SubscriptionStatus.ENDED, checkNotNull(kit.store.findSubscription(checkNotNull(first.subscriptionId))).status)
        assertNull(kit.store.findLiveSubscription("acc_1"))
        val again = subscription(txn = "txn_2").copy(paidAt = monthLater, periodStart = monthLater, periodEnd = monthLater.plusSeconds(30 * 86_400))

        val results = all(race(16) { record(again, basic) })

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(15, results.count { it.outcome == StoreRecordOutcome.ALREADY_RECORDED })
        assertEquals(1, subscriptionRows())
        assertEquals(2, orderRows())
        val line = checkNotNull(kit.store.findLiveSubscription("acc_1"))
        assertEquals(first.subscriptionId, line.id)
        assertEquals(SubscriptionStatus.ACTIVE, line.status)
        assertNull(line.endReason)
        assertEquals(2, kit.listener.paid.size)
    }

    @Test
    fun `a new token that links to our line ends the old line as replaced in the same step`() {
        val old = record(subscription(txn = "order_1", sub = "hash_old"), basic)

        val upgraded = record(subscription(txn = "order_2", sub = "hash_new", replaces = "hash_old"), basic)

        assertEquals(StoreRecordOutcome.RECORDED, upgraded.outcome)
        val ended = checkNotNull(kit.store.findSubscription(checkNotNull(old.subscriptionId)))
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.REPLACED, ended.endReason)
        assertNull(ended.activeKey)
        assertEquals(upgraded.subscriptionId, kit.store.findLiveSubscription("acc_1")?.id)
    }

    @Test
    fun `a renewal confirmed while the provider push of the same payment arrives is applied once and the push is never closed as a seat clash`() {
        assertEquals(StoreRecordOutcome.RECORDED, record(subscription(), basic).outcome)
        val handler = PaymentWebhookTaskHandler(kit.store, PaymentProviderRouter(listOf(gateway), PaymentProperties()), kit.convergence, kit.writer, pushedEvents)

        repeat(20) { round ->
            val txn = "renewal_$round"
            val start = monthLater.plusSeconds(round * 86_400L)
            val renewal = subscription(txn = txn).copy(paidAt = start, periodStart = start, periodEnd = start.plusSeconds(30 * 86_400))
            val push = PushedPayment(
                eventId = "evt_$txn", occurredAt = start, providerPaymentId = txn, total = PaymentAmount(3_900, "KRW"), tax = null, paidAt = start,
                providerSubscriptionId = "orig_1", periodStart = start, periodEnd = renewal.periodEnd, mode = PaymentMode.LIVE,
            )
            kit.inbox.receive(gateway, gateway.delivery(push))
            val event = checkNotNull(kit.store.findWebhookEventByKey("appstore", "evt_$txn"))

            val results = race(2) { n ->
                if (n == 0) record(renewal, basic).outcome
                else (1..3).firstNotNullOfOrNull { attempt ->
                    try {
                        handler.handle(PaymentTaskRun(PaymentTaskTypes.WEBHOOK, event.id.toString(), attempt, 3))
                        StoreRecordOutcome.RECORDED
                    } catch (_: PaymentTaskRetryException) {
                        null
                    }
                }
            }

            assertTrue(results.all { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
            val closed = checkNotNull(kit.store.findWebhookEvent(event.id))
            assertEquals(PaymentWebhookStatus.PROCESSED, closed.status, "round $round: ${closed.lastError}")
            assertEquals(1, PaymentDb.count("select count(*) from payment_orders where provider_payment_id = :id", "id" to txn), "round $round")
        }
    }
}
