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

    private fun payment(txn: String?, total: PaymentAmount = PaymentAmount(990, "USD"), at: Instant = t0, providerSub: String? = "psub_1", order: String? = null, ourSub: String? = null, price: String? = null, period: String? = null, credit: Long? = null, change: Boolean = false) =
        PushedPayment(
            eventId = "evt_${++seq}", occurredAt = at, providerPaymentId = txn ?: "txn_unknown", total = total, tax = 90, paidAt = at, ourOrderId = order, ourAccountId = "acc_1",
            ourSubscriptionId = ourSub, providerSubscriptionId = providerSub, periodStart = at, periodEnd = at.plusSeconds(31 * 86400),
            items = listOf(PushedItem(price ?: if (order != null) "pri_cheer-3000" else "pri_basic", 1)), period = period, credit = credit, subscriptionChange = change,
        )

    private fun state(status: PushedSubscriptionStatus, at: Instant, providerSub: String = "psub_1", txn: String? = null, items: List<PushedItem> = emptyList()) = PushedSubscription("evt_${++seq}", at, providerSub, status, null, null, t0, monthLater, txn, items = items)

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
    fun `an item without a provider price id is refused before any row is written - no pending row is left behind`() {
        push.unpriced += listOf("basic", "cheer-3000")

        assertFailsWith<PaymentUnavailableException> { pending() }
        assertFailsWith<PaymentUnavailableException> { kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")) }

        assertEquals(emptyList(), kit.store.allSubscriptions())
        assertEquals(emptyList(), kit.store.allOrders())
        assertEquals(0, push.transactions.size)
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
    fun `when the provider cannot create the transaction nothing opens, the row waits without a transaction and the next open creates exactly one`() {
        push.createFails = true
        assertFailsWith<PaymentUnavailableException> { pending() }
        assertFailsWith<PaymentUnavailableException> { kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")) }
        assertEquals(0, push.transactions.size)
        assertNull(kit.store.findLatestSubscription("acc_1"), "a subscription without a transaction is not shown and holds no seat")

        push.createFails = false
        val again = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1"), provider = "paddle")
        assertNotNull(again.billing?.attributes?.get("transactionId"))
        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle"))
        assertNotNull(order.checkout?.attributes?.get("transactionId"))
        assertEquals(2, push.transactions.size)
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
        // 동시에 만든 쪽의 거래가 결제 안 된 채 남을 수는 있지만(수용) 줄에 박힌 거래 id 는 하나다
        val stored = push.transactions.map { it.first }
        val subTxn = kit.store.findSubscriptionByDedupeKey(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("same")))?.firstTransactionId
        val orderTxn = kit.store.findOrderByDedupeKey(PaymentOrderIds.dedupeKey("acc_1", IdempotencyKey("same-order")))?.providerPaymentId
        assertNotNull(subTxn)
        assertNotNull(orderTxn)
        assertEquals(true, subTxn in stored && orderTxn in stored)
    }

    @Test
    fun `a first payment whose items differ from what the server put in the transaction does not turn the subscription on and is recorded for a person - a renewal is not compared`() {
        val pending = pending()
        feed(payment(pending.firstTransactionId, price = "pri_cheap"))
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, kit.store.listSubscriptionOrders(pending.id, 5).single().reviewReason)
        assertEquals(emptyList(), push.cancelCalls.toList())

        val other = kit.subscriptions.start("acc_2", "basic", IdempotencyKey("k2"), provider = "paddle").subscription
        feed(payment(other.firstTransactionId, providerSub = "psub_2", price = "pri_basic"))
        feed(payment("txn_r", providerSub = "psub_2", price = "pri_raised", at = monthLater))
        assertEquals(null, kit.store.listSubscriptionOrders(other.id, 5).first { it.providerPaymentId == "txn_r" }.reviewReason)

        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o9"), provider = "paddle")).order
        feed(payment(kit.order(order.id).providerPaymentId, providerSub = null, order = order.id, price = "pri_cheap"))
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(0, kit.listener.paid.count { it.id == order.id })
    }

    @Test
    fun `a first payment without a provider subscription id is recorded for a person, and subscription created with the transaction id links the row so the next payment turns it on`() {
        val pending = pending()
        feed(payment(pending.firstTransactionId, providerSub = null))
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, kit.store.listSubscriptionOrders(pending.id, 5).single().reviewReason)

        feed(state(PushedSubscriptionStatus.ACTIVE, t0, txn = pending.firstTransactionId))
        assertEquals("psub_1", sub(pending.id).providerSubscriptionId)
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status) // 연결만 - 켜지 않는다

        feed(payment("txn_next", at = monthLater))
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
    }

    @Test
    fun `a payment found on our row but refused by the cross check is recorded and flagged, not just dropped from the inbox`() {
        val pending = pending()
        feed(payment(pending.firstTransactionId, ourSub = "sub_somebody_else"))
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, kit.store.listSubscriptionOrders(pending.id, 5).single().reviewReason)
    }

    @Test
    fun `a refused transaction creation leaves one history line on the order with the status and the provider code`() {
        push.createFailure = dev.sumin.skeleton.common.http.ExternalHttpStatusException(
            "paddle", "POST", java.net.URI("https://sandbox-api.paddle.com/transactions"), 400,
            """{"error":{"code":"transaction_default_checkout_url_not_set","detail":"long text"}}""",
        )
        assertFailsWith<PaymentUnavailableException> { kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")) }
        val order = kit.store.allOrders().single()
        val failed = kit.store.listEvents(order.id).single { it.type == PaymentEventType.TRANSACTION_FAILED }
        assertEquals("400:transaction_default_checkout_url_not_set", failed.providerCode)
        assertEquals(PaymentActor.SYSTEM, failed.actor)
    }

    @Test
    fun `a failed payment attempt leaves one history line with Paddle's reason on the order, which stays pending and can be paid with the same transaction`() {
        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o1"), provider = "paddle")).order
        val txn = checkNotNull(kit.order(order.id).providerPaymentId)
        feed(PushedPaymentFailure("evt_f1", t0, txn, errorCode = "declined", ourOrderId = order.id))
        feed(PushedPaymentFailure("evt_f1", t0, txn, errorCode = "declined", ourOrderId = order.id)) // 같은 웹훅 재전송
        assertEquals(PaymentOrderStatus.PENDING, kit.order(order.id).status)
        val failed = kit.store.listEvents(order.id).filter { it.type == PaymentEventType.PAYMENT_FAILED }
        assertEquals(listOf("declined"), failed.map { it.providerCode })

        feed(payment(txn, providerSub = null, order = order.id))
        assertEquals(PaymentOrderStatus.PAID, kit.order(order.id).status)
    }

    @Test
    fun `a subscription event on a row still waiting for its first payment only links - it never turns the row on without a seat`() {
        val pending = pending()
        feed(state(PushedSubscriptionStatus.ACTIVE, t0, txn = pending.firstTransactionId)) // subscription.created: 연결만
        feed(state(PushedSubscriptionStatus.ACTIVE, t0.plusSeconds(5))) // subscription.activated 가 첫 결제보다 먼저 처리됐다
        assertEquals(SubscriptionStatus.PENDING, sub(pending.id).status)
        assertNull(sub(pending.id).activeKey)

        feed(payment(pending.firstTransactionId))
        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
        assertNotNull(sub(pending.id).activeKey)
    }

    @Test
    fun `the cycle of a Paddle subscription is Paddle's, not the local plan's`() {
        val pending = pending()
        feed(payment(pending.firstTransactionId, period = "P1Y"))
        assertEquals("P1Y", sub(pending.id).period)
        feed(PushedSubscription("evt_c", monthLater, "psub_1", PushedSubscriptionStatus.ACTIVE, null, null, t0, monthLater, null, period = "P3M"))
        assertEquals("P3M", sub(pending.id).period)
    }

    private fun buyerPayment(txn: String?, address: String?, providerSub: String? = null, order: String? = null) =
        payment(txn, providerSub = providerSub, order = order).copy(customerId = "ctm_1", addressId = address)

    @Test
    fun `a one-time payment whose buyer address is in Korea is paid and flagged for the operator - not blocked`() {
        push.buyerCountries["add_kr"] = "KR"
        push.buyerCountries["add_us"] = "US"
        val flagged = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o-kr"), provider = "paddle")).order
        feed(buyerPayment(kit.store.findOrder(flagged.id)!!.providerPaymentId, "add_kr", order = flagged.id))
        val clean = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o-us"), provider = "paddle")).order
        feed(buyerPayment(kit.store.findOrder(clean.id)!!.providerPaymentId, "add_us", order = clean.id))

        assertEquals(PaymentOrderStatus.PAID, kit.store.findOrder(flagged.id)!!.status)
        assertEquals(PaymentReviewReason.BUYER_COUNTRY_KR, kit.store.findOrder(flagged.id)!!.reviewReason)
        assertEquals(PaymentOrderStatus.PAID, kit.store.findOrder(clean.id)!!.status)
        assertNull(kit.store.findOrder(clean.id)!!.reviewReason)
    }

    @Test
    fun `a first subscription payment from a Korean address activates the subscription and flags its order`() {
        push.buyerCountries["add_kr"] = "KR"
        val pending = pending()
        feed(buyerPayment(pending.firstTransactionId, "add_kr", providerSub = "psub_1"))

        assertEquals(SubscriptionStatus.ACTIVE, sub(pending.id).status)
        assertEquals(PaymentReviewReason.BUYER_COUNTRY_KR, kit.store.listSubscriptionOrders(pending.id, 5).single().reviewReason)
    }

    @Test
    fun `when the address cannot be looked up the payment is applied as usual and nothing is flagged`() {
        push.buyerCountryFails = true
        val order = kit.orders.create(CreateOrderCommand("acc_1", "cheer", "cheer-3000", IdempotencyKey("o-x"), provider = "paddle")).order
        feed(buyerPayment(kit.store.findOrder(order.id)!!.providerPaymentId, "add_kr", order = order.id))

        assertEquals(PaymentOrderStatus.PAID, kit.store.findOrder(order.id)!!.status)
        assertNull(kit.store.findOrder(order.id)!!.reviewReason)
    }

    @Test
    fun `a renewal from a Korean address is not flagged again - only the first payment is`() {
        push.buyerCountries["add_kr"] = "KR"
        val pending = pending()
        feed(buyerPayment(pending.firstTransactionId, "add_kr", providerSub = "psub_1"))
        feed(buyerPayment("txn_renew", "add_kr", providerSub = "psub_1").copy(occurredAt = monthLater, paidAt = monthLater))

        val orders = kit.store.listSubscriptionOrders(pending.id, 5)
        assertEquals(2, orders.size)
        assertEquals(1, orders.count { it.reviewReason == PaymentReviewReason.BUYER_COUNTRY_KR })
        assertEquals(PaymentOrderKind.SUBSCRIPTION_INITIAL, orders.single { it.reviewReason == PaymentReviewReason.BUYER_COUNTRY_KR }.kind)
    }

    @Test
    fun `a change payment webhook records a change order and does not overwrite the subscription amount`() {
        val line = active()
        val before = sub(line.id)

        feed(payment("txn_chg", total = PaymentAmount(1_500, "USD"), at = t0.plusSeconds(3600), price = "pri_plus", change = true, credit = 300))

        val orders = kit.store.listSubscriptionOrders(line.id, 5)
        val change = orders.single { it.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE }
        assertEquals("plus", change.itemRef)
        assertEquals(PaymentAmount(1_500, "USD"), change.amount, "a change transaction total is already what was paid")
        assertEquals(0L, change.discountAmount)
        assertEquals(PaymentOrderStatus.PAID, change.status)
        assertEquals(setOf(PaymentOrderKind.SUBSCRIPTION_INITIAL, PaymentOrderKind.SUBSCRIPTION_CHANGE), orders.map { it.kind }.toSet())
        val after = sub(line.id)
        assertEquals(before.amount, after.amount)
        assertEquals(before.period, after.period)
        assertEquals(before.currentPeriodEnd, after.currentPeriodEnd)
        assertEquals(before.periodSeq, after.periodSeq)
        assertEquals(before.chargeOrderId, after.chargeOrderId)
    }

    @Test
    fun `a renewal paid partly by credit records what was paid, the credit as discount, and keeps the plan price`() {
        val line = active()

        feed(payment("txn_renew", total = PaymentAmount(990, "USD"), at = monthLater, credit = 300))

        val renewal = kit.store.listSubscriptionOrders(line.id, 5).single { it.kind == PaymentOrderKind.SUBSCRIPTION_RENEWAL }
        assertEquals(PaymentAmount(690, "USD"), renewal.amount)
        assertEquals(690L, renewal.paidAmount)
        assertEquals(990L, renewal.originalAmount)
        assertEquals(300L, renewal.discountAmount)
        assertEquals(PaymentAmount(990, "USD"), sub(line.id).amount)
    }

    @Test
    fun `a renewal fully paid by credit records a zero paid order and keeps the subscription amount`() {
        val line = active()

        feed(payment("txn_renew", total = PaymentAmount(990, "USD"), at = monthLater, credit = 990))

        val renewal = kit.store.listSubscriptionOrders(line.id, 5).single { it.kind == PaymentOrderKind.SUBSCRIPTION_RENEWAL }
        assertEquals(PaymentAmount(0, "USD"), renewal.amount)
        assertEquals(0L, renewal.paidAmount)
        assertEquals(990L, renewal.discountAmount)
        assertEquals(PaymentAmount(990, "USD"), sub(line.id).amount)
    }

    @Test
    fun `a subscription updated with another price switches the plan code and keeps the amount`() {
        val line = active()

        feed(state(PushedSubscriptionStatus.ACTIVE, Instant.parse("2026-10-14T10:00:00Z"), items = listOf(PushedItem("pri_plus", 1))))
        assertEquals("plus", sub(line.id).planCode)
        assertEquals(PaymentAmount(990, "USD"), sub(line.id).amount)

        feed(state(PushedSubscriptionStatus.ACTIVE, Instant.parse("2026-10-15T10:00:00Z"), items = listOf(PushedItem("pri_unknown_to_us_x", 1), PushedItem("pri_basic", 1))))
        assertEquals("plus", sub(line.id).planCode, "more than one item is not a plan switch")
        feed(state(PushedSubscriptionStatus.ACTIVE, Instant.parse("2026-10-16T10:00:00Z"), items = listOf(PushedItem("xyz", 1))))
        assertEquals("plus", sub(line.id).planCode, "a price we do not know is left alone")
    }
}
