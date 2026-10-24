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
    fun `a partial refund applied late does not end the subscription because another line made the order whole in the meantime`() {
        val active = activeSubscription()
        val order = firstOrder(active).id
        val partial = kit.refunds.refund("op_1", order, "OPERATOR", 1000, IdempotencyKey("r-a")).refund // 「구독도 해지」 켬, 부분
        // 이 줄의 회수 적용이 밀렸다: 표시를 되돌려 미적용으로 둔다
        kit.store.inTransaction { tx -> tx.lockOrder(order); tx.updateRefund(checkNotNull(tx.lockRefund(partial.id)).copy(appliedAt = null)) }
        kit.refunds.refund("op_1", order, "OPERATOR", null, IdempotencyKey("r-b"), endSubscription = false) // 나머지 전액, 구독은 둔다
        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(order).status)

        kit.applier.apply(partial.id)

        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertNull(sub(active.id).endReason)
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

    @Test
    fun `a Paddle approval that comes days later follows the choice on the line - kept stays, the default ends`() {
        val kept = providerSubscription("acc_1", "k1", "txn_k", "psub_k")
        val ended = providerSubscription("acc_2", "k2", "txn_e", "psub_e")
        kit.push.refundScript = dev.sumin.skeleton.payment.testing.FakePushGateway.RefundScript.PENDING_APPROVAL
        val keptOrder = kit.store.listSubscriptionOrders(kept.id, 1).single()
        val endedOrder = kit.store.listSubscriptionOrders(ended.id, 1).single()
        kit.refunds.refund("op_1", keptOrder.id, "OPERATOR", null, IdempotencyKey("r-k"), endSubscription = false)
        kit.refunds.refund("op_1", endedOrder.id, "OPERATOR", null, IdempotencyKey("r-e"))
        assertEquals(SubscriptionStatus.ACTIVE, sub(ended.id).status, "nothing ends while the provider is still reviewing")

        kit.advance(java.time.Duration.ofDays(3))
        for ((order, number) in listOf(keptOrder to "adj_s1", endedOrder to "adj_s2")) {
            val event = PushedRefund("evt_$number", kit.time.now(), PushedRefundKind.REFUND, number, checkNotNull(order.providerPaymentId), PushedRefundStatus.APPROVED, PaymentAmount(3_900, "KRW"))
            kit.deliver(kit.push.delivery(event), token = null, provider = "paddle")
        }
        repeat(2) { kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY, PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL)) }

        assertEquals(SubscriptionStatus.ACTIVE, sub(kept.id).status)
        assertEquals(SubscriptionEndReason.REFUNDED, sub(ended.id).endReason)
        assertEquals(listOf("psub_e"), kit.push.cancelCalls.map { it.first })
    }

    @Test
    fun `a closed line the provider later reports as done and that makes the order whole follows the line's own choice - the default ends the subscription, kept stays`() {
        val kept = providerSubscription("acc_1", "k1", "txn_k", "psub_k")
        val ended = providerSubscription("acc_2", "k2", "txn_e", "psub_e")
        kit.push.refundScript = dev.sumin.skeleton.payment.testing.FakePushGateway.RefundScript.PENDING_APPROVAL
        val keptOrder = kit.store.listSubscriptionOrders(kept.id, 1).single()
        val endedOrder = kit.store.listSubscriptionOrders(ended.id, 1).single()
        kit.refunds.refund("op_1", keptOrder.id, "OPERATOR", null, IdempotencyKey("r-k"), endSubscription = false)
        kit.refunds.refund("op_1", endedOrder.id, "OPERATOR", null, IdempotencyKey("r-e"))
        val decisions = listOf(keptOrder to "adj_s1", endedOrder to "adj_s2")
        fun feed(order: PaymentOrder, number: String, status: PushedRefundStatus) {
            val event = PushedRefund("evt_${number}_$status", kit.time.now(), PushedRefundKind.REFUND, number, checkNotNull(order.providerPaymentId), status, PaymentAmount(3_900, "KRW"))
            kit.deliver(kit.push.delivery(event), token = null, provider = "paddle")
        }
        for ((order, number) in decisions) feed(order, number, PushedRefundStatus.REJECTED)
        kit.runWebhooks()
        assertEquals(SubscriptionStatus.ACTIVE, sub(ended.id).status, "a rejected refund ends nothing")

        for ((order, number) in decisions) feed(order, number, PushedRefundStatus.APPROVED) // 거절로 닫은 줄을 결제사가 이뤄졌다고 알려 왔다
        repeat(2) { kit.runWebhooks(); kit.runTasks(setOf(PaymentTaskTypes.REFUND_APPLY, PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL)) }

        assertEquals(PaymentOrderStatus.REFUNDED, kit.order(endedOrder.id).status)
        assertEquals(SubscriptionStatus.ACTIVE, sub(kept.id).status)
        assertEquals(SubscriptionEndReason.REFUNDED, sub(ended.id).endReason)
        assertEquals(listOf("psub_e"), kit.push.cancelCalls.map { it.first })
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
