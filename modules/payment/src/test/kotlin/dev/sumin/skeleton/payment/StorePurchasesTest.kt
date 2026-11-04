package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.FakeStoreGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.PaymentTestOrders
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 스토어 구매를 장부에 넣는 길(인앱결제 K2): 이중 지급이 없고, 주인이 아닌 계정은 받지 못하고, 테스트 결제는 모드가 박히고, 좌석이 겹치면 돈은 장부에 남되 지급은 없다.
 * 이 시험은 메모리 저장소 위에서, 같은 약속의 두 방언 시험은 `payment-jdbc` 의 `StorePurchasesDbTest`.
 */
class StorePurchasesTest {
    private val gateway = FakeStoreGateway()
    private val kit = PaymentLedgerKit(push = gateway)
    private val t0: Instant = Instant.parse("2026-11-04T10:00:00Z")
    private val monthLater: Instant = Instant.parse("2026-12-04T10:00:00Z")

    private val cheer = StoreProduct.OneTime("cheer", "cheer-3000", "Cheer 3000")
    private val basic = StoreProduct.Subscription(kit.plans.plans.first { it.code == "basic" })

    private fun oneTime(txn: String = "txn_1", token: String? = StoreAccountTokens.of("acc_1"), mode: PaymentMode = PaymentMode.LIVE) = StorePurchase(
        mode = mode, kind = StorePurchaseKind.ONE_TIME, productId = "cheer_3000", providerPaymentId = txn, accountToken = token,
        amount = PaymentAmount(3_000, "KRW"), paidAt = t0, state = StorePurchaseState.PURCHASED,
    )

    private fun subscription(
        txn: String = "txn_1",
        sub: String = "orig_1",
        token: String? = StoreAccountTokens.of("acc_1"),
        start: Instant = t0,
        end: Instant = monthLater,
        mode: PaymentMode = PaymentMode.LIVE,
        replaces: String? = null,
        status: PushedSubscriptionStatus = PushedSubscriptionStatus.ACTIVE,
    ) = StorePurchase(
        mode = mode, kind = StorePurchaseKind.SUBSCRIPTION, productId = "premium", basePlanId = "monthly", providerPaymentId = txn, providerSubscriptionId = sub,
        replacesSubscriptionId = replaces, accountToken = token, amount = PaymentAmount(3_900, "KRW"), paidAt = start, periodStart = start, periodEnd = end,
        state = StorePurchaseState.PURCHASED, subscriptionStatus = status,
    )

    private fun record(purchase: StorePurchase, product: StoreProduct, account: String = "acc_1") = kit.storePurchases.record(gateway, account, purchase, product)

    private fun orders(provider: String = "appstore") = kit.store.listOrdersForOperator(OperatorOrderScope.ALL, OperatorOrderFilter(), null, null, 100).filter { it.provider == provider }

    private fun race(n: Int, task: () -> StoreRecordResult): List<StoreRecordResult> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        try {
            val futures = (1..n).map { pool.submit<StoreRecordResult> { start.await(); task() } }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a one-time purchase becomes a paid order and is granted once - the same purchase again changes nothing`() {
        val first = record(oneTime(), cheer)
        val again = record(oneTime(), cheer)

        assertEquals(StoreRecordOutcome.RECORDED, first.outcome)
        assertEquals(StoreRecordOutcome.ALREADY_RECORDED, again.outcome)
        assertEquals(first.orderId, again.orderId)
        val order = kit.order(checkNotNull(first.orderId))
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals("appstore", order.provider)
        assertEquals("txn_1", order.providerPaymentId)
        assertEquals(3_000, order.paidAmount)
        assertEquals(PaymentMode.LIVE, order.providerMode)
        assertEquals(1, orders().size)
        assertEquals(1, kit.listener.paid.size)
        assertEquals(1, gateway.acknowledged.size, "afterGrant runs once, after the grant is committed")
    }

    @Test
    fun `a test purchase keeps its own mode on the order so the revenue figures can leave it out`() {
        val result = record(oneTime(mode = PaymentMode.TEST), cheer)

        assertEquals(PaymentMode.TEST, kit.order(checkNotNull(result.orderId)).providerMode)
        assertEquals(1, kit.listener.paid.size, "a test purchase is granted all the same")
    }

    @Test
    fun `sixteen threads confirming the same one-time purchase leave one order and one grant`() {
        val results = race(16) { record(oneTime(), cheer) }

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(15, results.count { it.outcome == StoreRecordOutcome.ALREADY_RECORDED })
        assertEquals(1, orders().size)
        kit.runTasks()
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `a purchase that carries another account's token is refused and leaves nothing behind`() {
        val result = record(oneTime(token = StoreAccountTokens.of("acc_1")), cheer, account = "acc_2")

        assertEquals(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT, result.outcome)
        assertTrue(orders().isEmpty())
        assertEquals(0, kit.listener.paid.size)
        assertEquals(0, gateway.acknowledged.size)
    }

    @Test
    fun `a purchase without a token belongs to the first account that confirmed it - a second account is refused`() {
        val purchase = oneTime(token = null)

        assertEquals(StoreRecordOutcome.RECORDED, record(purchase, cheer, account = "acc_1").outcome)
        assertEquals(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT, record(purchase, cheer, account = "acc_2").outcome)
        assertEquals(StoreRecordOutcome.ALREADY_RECORDED, record(purchase, cheer, account = "acc_1").outcome)
        assertEquals(listOf("acc_1"), orders().map { it.accountId })
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `a purchase the store has not completed - waiting or revoked - records nothing`() {
        assertEquals(StoreRecordOutcome.NOT_PURCHASED, record(oneTime().copy(state = StorePurchaseState.PENDING), cheer).outcome)
        assertEquals(StoreRecordOutcome.NOT_PURCHASED, record(subscription().copy(state = StorePurchaseState.REVOKED), basic).outcome)
        assertTrue(orders().isEmpty())
        assertTrue(kit.store.allSubscriptions().isEmpty())
    }

    @Test
    fun `a first subscription becomes a store-managed line with the order of the period it is in`() {
        val result = record(subscription(), basic)

        assertEquals(StoreRecordOutcome.RECORDED, result.outcome)
        val line = checkNotNull(kit.store.findSubscription(checkNotNull(result.subscriptionId)))
        assertEquals(SubscriptionStatus.ACTIVE, line.status)
        assertEquals("orig_1", line.providerSubscriptionId)
        assertTrue(line.isProviderManaged)
        assertEquals("basic", line.planCode)
        assertEquals(t0, line.currentPeriodStart)
        assertEquals(monthLater, line.currentPeriodEnd)
        assertEquals("acc_1", line.activeKey)
        val order = kit.order(checkNotNull(result.orderId))
        assertEquals(PaymentOrderKind.SUBSCRIPTION_INITIAL, order.kind)
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(line.id, order.originRef)
        assertEquals(order.id, line.chargeOrderId)
        assertEquals(3_900, order.paidAmount)
        assertEquals(1, kit.listener.paid.size)
        assertEquals(1, gateway.acknowledged.size)
    }

    @Test
    fun `confirming the same subscription again restores it without a second line or a second grant - a newer period adds only the current period's order`() {
        val first = record(subscription(), basic)
        val again = record(subscription(), basic)
        assertEquals(StoreRecordOutcome.ALREADY_RECORDED, again.outcome)
        assertEquals(1, kit.store.allSubscriptions().size)
        assertEquals(1, orders().size)

        val renewed = record(subscription(txn = "txn_2", start = monthLater, end = Instant.parse("2027-01-04T10:00:00Z")), basic)

        assertEquals(StoreRecordOutcome.RECORDED, renewed.outcome)
        assertEquals(first.subscriptionId, renewed.subscriptionId)
        assertEquals(PaymentOrderKind.SUBSCRIPTION_RENEWAL, kit.order(checkNotNull(renewed.orderId)).kind)
        val line = checkNotNull(kit.store.findSubscription(checkNotNull(first.subscriptionId)))
        assertEquals(monthLater, line.currentPeriodStart)
        assertEquals(2, line.periodSeq)
        assertEquals(2, orders().size, "periods before the first confirmation are never back-filled")
        kit.runTasks()
        assertEquals(2, kit.listener.paid.size)
    }

    @Test
    fun `restoring a subscription whose auto-renew was switched off follows the store state`() {
        val first = record(subscription(), basic)

        record(subscription(status = PushedSubscriptionStatus.CANCELING), basic)

        assertEquals(SubscriptionStatus.CANCELING, checkNotNull(kit.store.findSubscription(checkNotNull(first.subscriptionId))).status)
    }

    @Test
    fun `an account that is already subscribed keeps the money in the ledger flagged for review and gets no second grant`() {
        val other = PaymentTestOrders.subscription(status = SubscriptionStatus.ACTIVE, activeKey = "acc_1")
        kit.store.inTransaction { it.insertSubscriptionIfAbsent(other) }

        val result = record(subscription(), basic)

        assertEquals(StoreRecordOutcome.ALREADY_SUBSCRIBED, result.outcome)
        val order = kit.order(checkNotNull(result.orderId))
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN, order.reviewReason)
        kit.runTasks()
        assertEquals(0, kit.listener.paid.size, "no grant for a second seat")
        assertEquals(0, gateway.acknowledged.size)
        assertEquals(other.id, kit.store.findLiveSubscription("acc_1")?.id)
        assertEquals(StoreRecordOutcome.ALREADY_SUBSCRIBED, record(subscription(), basic).outcome, "asking again does not add a second order")
        assertEquals(1, orders().size)
    }

    @Test
    fun `a new google token that links to our subscription ends the old line as replaced and opens the new one in the same step`() {
        val old = record(subscription(txn = "order_1", sub = "hash_old"), basic)

        val upgraded = record(subscription(txn = "order_2", sub = "hash_new", replaces = "hash_old", start = monthLater, end = Instant.parse("2027-01-04T10:00:00Z")), basic)

        assertEquals(StoreRecordOutcome.RECORDED, upgraded.outcome)
        val ended = checkNotNull(kit.store.findSubscription(checkNotNull(old.subscriptionId)))
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.REPLACED, ended.endReason)
        assertNull(ended.activeKey)
        assertEquals(upgraded.subscriptionId, kit.store.findLiveSubscription("acc_1")?.id)
        assertNotNull(kit.store.findSubscriptionByProviderId("appstore", "hash_new"))
    }

    @Test
    fun `sixteen threads confirming the same first subscription leave one line one order and one grant`() {
        val results = race(16) { record(subscription(), basic) }

        assertEquals(1, results.count { it.outcome == StoreRecordOutcome.RECORDED })
        assertEquals(15, results.count { it.outcome == StoreRecordOutcome.ALREADY_RECORDED })
        assertEquals(1, kit.store.allSubscriptions().size)
        assertEquals(1, orders().size)
        kit.runTasks()
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `a subscription another account confirmed first is refused - with or without a token`() {
        record(subscription(token = null), basic, account = "acc_1")

        assertEquals(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT, record(subscription(token = null), basic, account = "acc_2").outcome)
        assertEquals(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT, record(subscription(), basic, account = "acc_2").outcome)
        assertEquals(listOf("acc_1"), kit.store.allSubscriptions().map { it.accountId })
        assertEquals(1, orders().size)
    }

    @Test
    fun `a subscription the store already ended is not given a line - and an ended line is not revived`() {
        assertEquals(StoreRecordOutcome.NOT_PURCHASED, record(subscription(status = PushedSubscriptionStatus.ENDED), basic).outcome)
        assertTrue(kit.store.allSubscriptions().isEmpty())

        record(subscription(), basic)
        record(subscription(status = PushedSubscriptionStatus.ENDED), basic)
        assertEquals(StoreRecordOutcome.SUBSCRIPTION_ENDED, record(subscription(), basic).outcome)
    }

    @Test
    fun `a product of the other kind than the purchase is a programming error`() {
        assertFailsWith<IllegalArgumentException> { record(oneTime(), basic) }
        assertFailsWith<IllegalArgumentException> { record(subscription(), cheer) }
    }
}
