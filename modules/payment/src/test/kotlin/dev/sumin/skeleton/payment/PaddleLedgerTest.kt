package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.FakePushGateway
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Paddle 처럼 서명된 웹훅이 곧 사실인 결제사가 장부에 닿는 길 — 중복 · 순서 뒤바뀜 · 계정 매칭 · 우리 갱신 작업이 과금하지 않는 것 · 해지. */
class PaddleLedgerTest {
    private val push = FakePushGateway()
    private val kit = PaymentLedgerKit(push = push)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private val monthLater: Instant = Instant.parse("2026-11-13T10:00:00Z")
    private var seq = 0

    private fun pending(account: String = "acc_1"): PaymentSubscription =
        kit.subscriptions.start(account, "basic", IdempotencyKey("k-$account"), provider = "paddle").subscription

    private fun payment(sub: String?, txn: String, account: String? = "acc_1", total: PaymentAmount = PaymentAmount(990, "USD"), at: Instant = t0, order: String? = null, providerSub: String? = "psub_1", price: String? = null) =
        PushedPayment(
            eventId = "evt_${++seq}", occurredAt = at, providerPaymentId = txn, total = total, tax = 90, paidAt = at, ourOrderId = order, ourAccountId = account,
            ourSubscriptionId = sub, providerSubscriptionId = providerSub, periodStart = at, periodEnd = at.plusSeconds(31 * 86400),
            items = listOf(PushedItem(price ?: if (order != null) "pri_cheer-3000" else "pri_basic", 1)),
        )

    private fun state(sub: String?, status: PushedSubscriptionStatus, at: Instant, account: String? = "acc_1") =
        PushedSubscription("evt_${++seq}", at, "psub_1", status, account, sub, t0, monthLater)

    private fun feed(event: PushedPaymentEvent): WebhookReceipt = kit.deliver(push.delivery(event), token = null, provider = "paddle").also { kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL)) }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    @Test
    fun `the first payment activates the pending subscription and records the amount and currency Paddle sent`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1", total = PaymentAmount(1250, "EUR")))

        val active = sub(pending.id)
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals("psub_1", active.providerSubscriptionId)
        val order = kit.store.listSubscriptionOrders(pending.id, 5).single()
        assertEquals(PaymentOrderKind.SUBSCRIPTION_INITIAL, order.kind)
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(PaymentAmount(1250, "EUR"), order.amount)
        assertEquals(1250L, order.paidAmount)
    }

    @Test
    fun `the same webhook twice and the same transaction under a new event id apply once`() {
        val pending = pending()
        val first = payment(pending.id, "txn_1")
        feed(first)
        assertEquals(WebhookReceipt.DUPLICATE, feed(first))
        feed(payment(pending.id, "txn_1")) // 결제사가 같은 거래를 다른 사건 id 로 다시 보냈다

        assertEquals(1, kit.store.listSubscriptionOrders(pending.id, 5).size)
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `our renewal task never charges a Paddle subscription`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1"))
        kit.time.set(checkNotNull(sub(pending.id).currentPeriodEnd).plusSeconds(3600))

        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, pending.id, 1, 3))

        assertEquals(0, kit.gateway.chargeCalls.size)
        assertEquals(1, kit.store.listSubscriptionOrders(pending.id, 5).size)
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
    }

    @Test
    fun `a renewal is a new paid order and keeps the subscription in use`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1"))
        feed(payment(pending.id, "txn_2", at = monthLater, providerSub = "psub_1", account = null))

        val orders = kit.store.listSubscriptionOrders(pending.id, 5)
        assertEquals(setOf(PaymentOrderKind.SUBSCRIPTION_INITIAL, PaymentOrderKind.SUBSCRIPTION_RENEWAL), orders.map { it.kind }.toSet())
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
    }

    @Test
    fun `an update that arrives before the creation still leaves the right state`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1"))
        feed(state(pending.id, PushedSubscriptionStatus.CANCELING, Instant.parse("2026-10-13T10:05:00Z")))
        feed(state(pending.id, PushedSubscriptionStatus.ACTIVE, Instant.parse("2026-10-13T10:00:00Z"))) // 더 일찍 일어난 사건이 늦게 왔다

        assertEquals(SubscriptionStatus.CANCELING, sub(pending.id).status)
    }

    @Test
    fun `canceled ends the subscription and frees the seat`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1"))
        feed(state(pending.id, PushedSubscriptionStatus.ENDED, Instant.parse("2026-11-13T10:00:00Z")))

        val ended = sub(pending.id)
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertNull(ended.activeKey)
    }

    @Test
    fun `an event whose account is not the owner of our subscription changes nothing`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1", account = "acc_other"))

        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        // 돈은 받았으니 기록하되 확인 필요로 - 구독은 켜지지 않는다
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, kit.store.listSubscriptionOrders(pending.id, 5).single().reviewReason)
    }

    @Test
    fun `a one-time payment marks our order paid with the webhook currency and amount, once`() {
        val created = kit.create().order
        kit.store.inTransaction { tx -> tx.lockOrder(created.id)?.let { tx.updateOrder(it.copy(provider = "paddle")) } }
        val event = payment(null, "txn_9", total = PaymentAmount(499, "USD"), order = created.id, providerSub = null)
        feed(event)
        feed(payment(null, "txn_9", total = PaymentAmount(499, "USD"), order = created.id, providerSub = null))

        val paid = kit.order(created.id)
        assertEquals(PaymentOrderStatus.PAID, paid.status)
        assertEquals(PaymentAmount(499, "USD"), paid.amount)
        assertEquals(1, kit.listener.paid.size)
    }

    @Test
    fun `cancel asks Paddle to cancel at the end of the period and the subscription becomes CANCELING`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1"))

        val canceled = kit.subscriptions.cancel("acc_1", pending.id)

        assertEquals(listOf("psub_1" to true), push.cancelCalls.toList())
        assertEquals(SubscriptionStatus.CANCELING, canceled.status)
    }

    @Test
    fun `when Paddle refuses the cancel the subscription stays as it was`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1"))
        push.cancelFails = true

        assertFailsWith<IllegalStateException> { kit.subscriptions.cancel("acc_1", pending.id) }
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
        assertNotNull(sub(pending.id).providerSubscriptionId)
    }
}

class PaddleLedgerReviewTest {
    private val push = FakePushGateway()
    private val kit = PaymentLedgerKit(push = push)
    private val t0: Instant = Instant.parse("2026-10-13T10:00:00Z")
    private var seq = 100

    private fun pending(): PaymentSubscription = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k"), provider = "paddle").subscription

    private fun payment(sub: String?, txn: String, price: String, order: String? = null, providerSub: String? = "psub_1", account: String? = "acc_1") = PushedPayment(
        eventId = "evt_${++seq}", occurredAt = t0, providerPaymentId = txn, total = PaymentAmount(100, "USD"), tax = 0, paidAt = t0, ourOrderId = order, ourAccountId = account,
        ourSubscriptionId = sub, providerSubscriptionId = providerSub, items = listOf(PushedItem(price, 1)),
    )

    private fun feed(event: PushedPaymentEvent) { kit.deliver(push.delivery(event), token = null, provider = "paddle"); kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL)) }
    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    @Test
    fun `a cheap price with an expensive plan or order in the custom data is paid for nothing and needs a person`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1", price = "pri_cheap"))
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        val recorded = kit.store.listSubscriptionOrders(pending.id, 5).single()
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, recorded.reviewReason)
        assertEquals(0, kit.listener.paid.size)

        val order = kit.create().order
        kit.store.inTransaction { tx -> tx.lockOrder(order.id)?.let { tx.updateOrder(it.copy(provider = "paddle")) } }
        feed(payment(null, "txn_2", price = "pri_cheap", order = order.id, providerSub = null))
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(0, kit.listener.paid.size)
    }

    @Test
    fun `a second Paddle subscription on the same row is not applied and is canceled at once`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1", price = "pri_basic", providerSub = "psub_1"))
        feed(payment(pending.id, "txn_2", price = "pri_basic", providerSub = "psub_2"))

        assertEquals("psub_1", sub(pending.id).providerSubscriptionId)
        val second = kit.store.listSubscriptionOrders(pending.id, 5).first { it.providerPaymentId == "txn_2" }
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, second.reviewReason)
        kit.runTasks(setOf(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))
        assertEquals(listOf("psub_2" to false), push.cancelCalls.toList())
    }

    @Test
    fun `erasing the account cancels the Paddle subscription at once`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1", price = "pri_basic"))
        kit.store.inTransaction { tx -> kit.lifecycle.end(tx, checkNotNull(tx.lockSubscription(pending.id)), SubscriptionEndReason.ACCOUNT_ERASED) }
        kit.runTasks(setOf(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))
        assertEquals(listOf("psub_1" to false), push.cancelCalls.toList())
    }

    @Test
    fun `a subscription event alone never turns a pending row on - the payment with the right price does, in either order`() {
        val pending = pending()
        feed(PushedSubscription("evt_${++seq}", t0, "psub_1", PushedSubscriptionStatus.ACTIVE, "acc_1", pending.id, t0, t0.plusSeconds(86400)))
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)

        feed(payment(pending.id, "txn_1", price = "pri_basic"))
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
    }

    @Test
    fun `a connected subscription renewed at a price the config no longer lists is recorded and fulfilled, with no cancel`() {
        val pending = pending()
        feed(payment(pending.id, "txn_1", price = "pri_basic"))
        feed(payment(pending.id, "txn_2", price = "pri_basic_raised", account = null))

        val renewal = kit.store.listSubscriptionOrders(pending.id, 5).first { it.providerPaymentId == "txn_2" }
        assertEquals(PaymentOrderKind.SUBSCRIPTION_RENEWAL, renewal.kind)
        assertEquals(null, renewal.reviewReason)
        kit.runTasks(setOf(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))
        assertEquals(emptyList(), push.cancelCalls.toList())
    }
}
