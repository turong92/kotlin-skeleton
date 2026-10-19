package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 운영자가 구독 결제를 전액 환불할 때 「구독도 해지」를 고른다(기본 켜짐). 끄면 환불만 하고 구독은 다음 갱신에서 계속 과금된다.
 * 선택은 환불 줄에 남아 결제사가 며칠 뒤 끝내는 환불에도 그대로 적용된다. 칸을 안 보내면 지금 동작 그대로다.
 */
class RefundEndsSubscriptionChoiceTest {
    private val kit = PaymentLedgerKit()
    private val auth = mapOf("authKey" to "auth_1")

    private fun activeSubscription(account: String = "acc_1", key: String = "k1"): PaymentSubscription {
        val started = kit.subscriptions.start(account, "basic", IdempotencyKey(key)).subscription
        return kit.subscriptions.activate(account, started.id, auth)
    }

    private fun firstOrder(sub: PaymentSubscription) = kit.store.listSubscriptionOrders(sub.id, 1).single()

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    @Test
    fun `leaving the choice out ends the subscription exactly as before`() {
        val active = activeSubscription()

        kit.refunds.refund("op_1", firstOrder(active).id, "OPERATOR", null, IdempotencyKey("r-1"))

        assertEquals(SubscriptionEndReason.REFUNDED, sub(active.id).endReason)
    }

    @Test
    fun `refunding without ending keeps the subscription and the next renewal is charged`() {
        val active = activeSubscription()

        val outcome = kit.refunds.refund("op_1", firstOrder(active).id, "OPERATOR", null, IdempotencyKey("r-1"), endSubscription = false)

        assertEquals(PaymentRefundStatus.SUCCEEDED, outcome.refund.status)
        assertTrue(outcome.refund.keepSubscription)
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(firstOrder(active).id).status)
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertNull(sub(active.id).endReason)
        kit.time.set(checkNotNull(sub(active.id).currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))
        assertEquals(2, kit.gateway.chargeCalls.size, "the renewal was charged")
    }

    @Test
    fun `the same key with the other choice is a conflict, and the provider is not asked again`() {
        val active = activeSubscription()
        val order = firstOrder(active).id
        kit.refunds.refund("op_1", order, "OPERATOR", 1000, IdempotencyKey("r-1"), endSubscription = false)
        val calls = kit.gateway.refundCalls.size

        assertFailsWith<PaymentIdempotencyConflictException> { kit.refunds.refund("op_1", order, "OPERATOR", 1000, IdempotencyKey("r-1")) }

        val same = kit.refunds.refund("op_1", order, "OPERATOR", 1000, IdempotencyKey("r-1"), endSubscription = false)
        assertTrue(same.replayed)
        assertEquals(calls, kit.gateway.refundCalls.size)
    }

    @Test
    fun `approving a request without ending keeps the subscription, and the choice is on the line`() {
        val active = activeSubscription()
        val order = firstOrder(active)
        val request = kit.refunds.request("acc_1", order.id, "CUSTOMER_REQUEST", null, IdempotencyKey("u-1")).refund

        val outcome = kit.refunds.approve("op_1", request.id, endSubscription = false)

        assertTrue(outcome.refund.keepSubscription)
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
    }

    @Test
    fun `a partial refund never ends the subscription whichever choice was made`() {
        val active = activeSubscription()

        kit.refunds.refund("op_1", firstOrder(active).id, "OPERATOR", 1000, IdempotencyKey("r-1"))

        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertFalse(kit.store.listRefunds(firstOrder(active).id).single().keepSubscription)
    }

    @Test
    fun `ending a subscription the provider renews cancels it at the provider, and keeping it sends nothing`() {
        val kept = providerSubscription("acc_1", "k1", "txn_k", "psub_k")
        val ended = providerSubscription("acc_2", "k2", "txn_e", "psub_e")

        kit.refunds.refund("op_1", kit.store.listSubscriptionOrders(kept.id, 1).single().id, "OPERATOR", null, IdempotencyKey("r-k"), endSubscription = false)
        kit.refunds.refund("op_1", kit.store.listSubscriptionOrders(ended.id, 1).single().id, "OPERATOR", null, IdempotencyKey("r-e"))
        kit.runTasks(setOf(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL))

        assertEquals(listOf("psub_e"), kit.push.cancelCalls.map { it.first })
        assertEquals(SubscriptionStatus.ACTIVE, sub(kept.id).status)
    }

    /** 결제사가 갱신하는 구독(Paddle 모양) - 첫 결제가 웹훅으로 들어와 켜진 줄. 환불은 결제사 환불 능력이 필요해 이 대역의 환불 대본을 쓴다. */
    private fun providerSubscription(account: String, key: String, txn: String, providerSub: String): PaymentSubscription {
        val pending = kit.subscriptions.start(account, "basic", IdempotencyKey(key), provider = "paddle").subscription
        val event = PushedPayment(
            eventId = "evt_$txn", occurredAt = kit.time.now(), providerPaymentId = checkNotNull(pending.firstTransactionId), total = PaymentAmount(3_900, "KRW"), tax = null, paidAt = kit.time.now(),
            ourAccountId = account, providerSubscriptionId = providerSub, items = listOf(PushedItem("pri_basic", 1)),
        )
        kit.deliver(kit.push.delivery(event), token = null, provider = "paddle")
        kit.runWebhooks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        return sub(pending.id)
    }
}
