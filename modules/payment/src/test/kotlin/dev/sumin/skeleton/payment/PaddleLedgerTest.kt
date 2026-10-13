package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Paddle 처럼 서명된 웹훅이 곧 사실이고 **거래를 서버가 먼저 만드는** 결제사가 장부에 닿는 길. 브라우저는 거래 id 만 연다 - 무엇을 샀는지는 서버가 정했다.
 * 지키는 것: 모르는 거래는 반영 안 됨 · 같은 웹훅 두 번은 한 번 · 순서가 뒤바뀐 구독 사건 · 연결된 구독의 거래만 갱신 · 우리 갱신 작업이 과금하지 않음 · 해지 · 탈퇴 · 거래 생성 실패 시 줄이 안 남음.
 */
class PaddleLedgerTest {
    private val push = FakePushGateway()
    private val kit = PaymentLedgerKit(push = push)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private val monthLater: Instant = Instant.parse("2026-11-13T10:00:00Z")
    private var seq = 0

    private fun pending(key: String = "k1"): PaymentSubscription = kit.subscriptions.start("acc_1", "basic", IdempotencyKey(key), provider = "paddle").subscription

    private fun payment(txn: String?, total: PaymentAmount = PaymentAmount(990, "USD"), at: Instant = t0, providerSub: String? = "psub_1", order: String? = null, ourSub: String? = null) =
        PushedPayment(
            eventId = "evt_${++seq}", occurredAt = at, providerPaymentId = txn ?: "txn_unknown", total = total, tax = 90, paidAt = at, ourOrderId = order, ourAccountId = "acc_1",
            ourSubscriptionId = ourSub, providerSubscriptionId = providerSub, periodStart = at, periodEnd = at.plusSeconds(31 * 86400),
        )

    private fun state(status: PushedSubscriptionStatus, at: Instant, providerSub: String = "psub_1") = PushedSubscription("evt_${++seq}", at, providerSub, status, null, null, t0, monthLater)

    private fun feed(event: PushedPaymentEvent): WebhookReceipt = kit.deliver(push.delivery(event), token = null, provider = "paddle").also { kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL)) }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    private fun active(): PaymentSubscription = pending().also { feed(payment(it.firstTransactionId)) }

    @Test
    fun `the server creates the transaction with its own price and ids, and the checkout opens that transaction only`() {
        val started = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "paddle")
        val (txn, request) = push.transactions.single()
        assertEquals("basic", request.itemRef)
        assertEquals(1, request.quantity)
        assertEquals(started.subscription.id, request.customData[PushedEvents.SUBSCRIPTION_KEY])
        assertEquals("acc_1", request.customData[PushedEvents.ACCOUNT_KEY])
        assertEquals(txn, started.subscription.firstTransactionId)
        assertEquals(txn, started.billing?.attributes?.get("transactionId"))
    }

    @Test
    fun `opening the same pending subscription again gives the same transaction and creates no new one`() {
        val first = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "paddle")
        val again = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "paddle")
        assertEquals(first.subscription.id, again.subscription.id)
        assertEquals(first.billing?.attributes?.get("transactionId"), again.billing?.attributes?.get("transactionId"))
        assertEquals(1, push.transactions.size)
    }

    @Test
    fun `when the provider cannot create the transaction nothing is started and no row is left`() {
        push.createFails = true
        assertFailsWith<PaymentUnavailableException> { pending() }
        assertNull(kit.store.findLiveSubscription("acc_1"))
        assertNull(kit.store.findSubscriptionByDedupeKey(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("k1"))))
        assertFailsWith<PaymentUnavailableException> { kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")) }
        assertNull(kit.store.findOrderByDedupeKey(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("o1"))))
    }

    @Test
    fun `the first payment is found by the transaction id the server stored, links the provider subscription and records the webhook amount and currency`() {
        val pending = pending()
        feed(payment(pending.firstTransactionId, total = PaymentAmount(1250, "EUR")))

        val active = sub(pending.id)
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals("psub_1", active.providerSubscriptionId)
        val order = kit.store.listSubscriptionOrders(pending.id, 5).single()
        assertEquals(PaymentOrderKind.SUBSCRIPTION_INITIAL, order.kind)
        assertEquals(PaymentAmount(1250, "EUR"), order.amount)
    }

    @Test
    fun `a completed transaction the server never created changes nothing, even with our subscription id in its custom data`() {
        val pending = pending()
        feed(payment("txn_not_ours", ourSub = pending.id))

        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        assertEquals(0, kit.store.listSubscriptionOrders(pending.id, 5).size)
    }

    @Test
    fun `the same webhook twice and the same transaction under a new event id apply once`() {
        val pending = pending()
        val first = payment(pending.firstTransactionId)
        feed(first)
        assertEquals(WebhookReceipt.DUPLICATE, feed(first))
        feed(payment(pending.firstTransactionId))

        assertEquals(1, kit.store.listSubscriptionOrders(pending.id, 5).size)
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `our renewal task never charges a Paddle subscription`() {
        val pending = active()
        kit.time.set(checkNotNull(sub(pending.id).currentPeriodEnd).plusSeconds(3600))

        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, pending.id, 1, 3))

        assertEquals(0, kit.gateway.chargeCalls.size)
        assertEquals(1, kit.store.listSubscriptionOrders(pending.id, 5).size)
    }

    @Test
    fun `a renewal is a transaction of the linked provider subscription and lifts past due`() {
        val pending = active()
        feed(state(PushedSubscriptionStatus.PAST_DUE, Instant.parse("2026-11-13T09:00:00Z")))
        assertEquals(SubscriptionStatus.PAST_DUE, sub(pending.id).status)

        feed(payment("txn_renew", at = monthLater))

        assertEquals(setOf(PaymentOrderKind.SUBSCRIPTION_INITIAL, PaymentOrderKind.SUBSCRIPTION_RENEWAL), kit.store.listSubscriptionOrders(pending.id, 5).map { it.kind }.toSet())
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
    }

    @Test
    fun `a payment that is not a transaction of the linked provider subscription is no renewal - past due cannot be lifted with a cheap one-off`() {
        val pending = active()
        feed(state(PushedSubscriptionStatus.PAST_DUE, Instant.parse("2026-11-13T09:00:00Z")))

        feed(payment("txn_oneoff", at = monthLater, providerSub = null, ourSub = pending.id)) // 단건 거래 - 구독 id 가 없다
        feed(payment("txn_other", at = monthLater, providerSub = "psub_other", ourSub = pending.id)) // 다른 결제사 구독의 거래

        assertEquals(SubscriptionStatus.PAST_DUE, sub(pending.id).status)
        assertEquals(1, kit.store.listSubscriptionOrders(pending.id, 5).size)
    }

    @Test
    fun `a subscription event before the first payment is not applied, and a later one for the linked id is, newest wins`() {
        val pending = pending()
        feed(state(PushedSubscriptionStatus.CANCELING, Instant.parse("2026-10-13T10:05:00Z"))) // 아직 연결 전 - 반영 안 됨
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)

        feed(payment(pending.firstTransactionId))
        feed(state(PushedSubscriptionStatus.CANCELING, Instant.parse("2026-10-13T10:05:00Z")))
        feed(state(PushedSubscriptionStatus.ACTIVE, Instant.parse("2026-10-13T10:00:00Z"))) // 더 일찍 일어난 사건이 늦게 왔다
        assertEquals(SubscriptionStatus.CANCELING, sub(pending.id).status)
    }

    @Test
    fun `a state event of a provider subscription that is not linked to any of our rows changes nothing`() {
        val pending = active()
        feed(state(PushedSubscriptionStatus.ENDED, Instant.parse("2026-11-13T10:00:00Z"), providerSub = "psub_other"))
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
    }

    @Test
    fun `canceled ends the subscription and frees the seat`() {
        val pending = active()
        feed(state(PushedSubscriptionStatus.ENDED, Instant.parse("2026-11-13T10:00:00Z")))

        val ended = sub(pending.id)
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertNull(ended.activeKey)
    }

    @Test
    fun `a one-time order is created with a server-made transaction and paid by that transaction only, once, in the currency Paddle sent`() {
        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")).order
        val (txn, request) = push.transactions.single()
        assertEquals("cheer-3000", request.itemRef)
        assertEquals(order.id, request.customData[PushedEvents.ORDER_KEY])
        assertEquals(txn, kit.order(order.id).providerPaymentId)

        feed(payment("txn_not_ours", total = PaymentAmount(1, "USD"), providerSub = null, order = order.id))
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)

        feed(payment(txn, total = PaymentAmount(499, "USD"), providerSub = null, order = order.id))
        feed(payment(txn, total = PaymentAmount(499, "USD"), providerSub = null, order = order.id))
        val paid = kit.order(order.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(PaymentAmount(499, "USD"), paid.amount)
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `cancel asks Paddle to cancel at the end of the period and the subscription becomes CANCELING`() {
        val pending = active()
        val canceled = kit.subscriptions.cancel("acc_1", pending.id)
        assertEquals(listOf("psub_1" to true), push.cancelCalls.toList())
        assertEquals(SubscriptionStatus.CANCELING, canceled.status)
    }

    @Test
    fun `when Paddle refuses the cancel the subscription stays as it was`() {
        val pending = active()
        push.cancelFails = true
        assertFailsWith<IllegalStateException> { kit.subscriptions.cancel("acc_1", pending.id) }
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
        assertNotNull(sub(pending.id).providerSubscriptionId)
    }

    @Test
    fun `erasing the account cancels the Paddle subscription at once`() {
        val pending = active()
        kit.store.inTransaction { tx -> kit.lifecycle.end(tx, checkNotNull(tx.lockSubscription(pending.id)), SubscriptionEndReason.ACCOUNT_ERASED) }
        kit.runTasks(setOf(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))
        assertEquals(listOf("psub_1" to false), push.cancelCalls.toList())
    }

    @Test
    fun `a payment that arrives for an ended subscription is recorded for a person and the provider subscription is canceled when the account was erased`() {
        val pending = active()
        kit.store.inTransaction { tx -> kit.lifecycle.end(tx, checkNotNull(tx.lockSubscription(pending.id)), SubscriptionEndReason.ACCOUNT_ERASED) }
        feed(payment("txn_late", at = monthLater))
        val late = kit.store.listSubscriptionOrders(pending.id, 5).first { it.providerPaymentId == "txn_late" }
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, late.reviewReason)
    }

    @Test
    fun `two concurrent starts with the same key create one transaction and answer with the same row`() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val go = java.util.concurrent.CountDownLatch(1)
        try {
            val subs = (1..2).map { pool.submit<PaymentSubscription> { go.await(); kit.subscriptions.start("acc_1", "basic", IdempotencyKey("same"), provider = "paddle").subscription } }
            val orders = (1..2).map { pool.submit<PaymentOrder> { go.await(); kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("same-order"), provider = "paddle")).order } }
            go.countDown()
            val ids = subs.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS).id }.toSet()
            val orderIds = orders.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS).id }.toSet()
            assertEquals(1, ids.size)
            assertEquals(1, orderIds.size)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2, push.transactions.size, "one transaction for the subscription and one for the order")
    }
}
