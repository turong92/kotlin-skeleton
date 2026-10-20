package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 결제사가 먼저 예약해야 창이 열리고(`prepareBilling`) **등록이 곧 첫 결제**인 결제사(카카오페이 모양)의 구독 걸음 - 이중 과금 · 남의 것 · 토큰 노출 · 모름의 처리가 깨지면 사고 나는 곳이다.
 * 가짜 결제사는 `ScriptedGateway` 의 스위치(`prepareReferences` · `registering`)로 만든다.
 */
class RegistrationSubscriptionTest {
    private val kit = PaymentLedgerKit().also {
        it.gateway.prepareReferences = true
        it.gateway.registering = true
    }
    private val pgToken = mapOf("pg_token" to "pgt_1")

    private fun started(account: String = "acc_1", key: String = "k1") = kit.subscriptions.start(account, "basic", IdempotencyKey(key), returnOrigin = "http://localhost:5173")

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    @Test
    fun `starting reserves at the provider outside any lock, hands the reference to the row, and a resend before the card reserves again with a new number`() {
        val first = started()

        assertEquals("ref_1", first.subscription.firstTransactionId)
        val call = kit.gateway.prepareCalls.single()
        assertEquals(first.subscription.id, call.subscriptionId)
        assertEquals(PaymentOrderIds.initialOrderId(first.subscription.id), call.firstOrderId)
        assertEquals("http://localhost:5173", call.returnOrigin)

        val again = started()
        assertTrue(again.replayed)
        assertEquals("ref_2", sub(first.subscription.id).firstTransactionId, "the new window's number replaces the old one")
        assertEquals(2, kit.gateway.prepareCalls.size)
    }

    @Test
    fun `the first payment is the registration - one provider call with the reserved number, the token stored encrypted and never in a response string`() {
        val sub = started().subscription

        val active = kit.subscriptions.activate("acc_1", sub.id, pgToken)

        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        val call = kit.gateway.registerCalls.single()
        assertEquals("ref_1", call.reference)
        assertEquals(PaymentOrderIds.initialOrderId(sub.id), call.orderId)
        assertEquals(0, kit.gateway.issueCalls.size, "no separate card registration step")
        val order = kit.store.listSubscriptionOrders(sub.id, 5).single()
        assertEquals(PaymentOrderIds.initialOrderId(sub.id), order.id)
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals("ref_1", order.providerPaymentId)
        val stored = checkNotNull(sub(sub.id).billingToken)
        assertEquals("sid_1", ReversibleTestEncryptor.decrypt(stored))
        listOf(active.toString(), order.toString(), call.toString()).forEach { assertFalse(it.contains("sid_1"), it) }
        assertEquals("SHINHAN", sub(sub.id).cardIssuer)
    }

    @Test
    fun `a first payment that is received without a readable billing key starts the subscription but marks the order for the operator at once`() {
        val sub = started().subscription
        kit.gateway.registerWithoutToken = true

        val active = kit.subscriptions.activate("acc_1", sub.id, pgToken)

        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertNull(sub(sub.id).billingToken)
        assertEquals(PaymentReviewReason.SUBSCRIPTION_TOKEN_MISSING, kit.order(PaymentOrderIds.initialOrderId(sub.id)).reviewReason)
    }

    @Test
    fun `pressing activate again or from two threads reaches the provider once`() {
        val sub = started().subscription

        repeat(3) { kit.subscriptions.activate("acc_1", sub.id, pgToken) }

        assertEquals(1, kit.gateway.registerCalls.size)
        assertEquals(1, kit.store.listSubscriptionOrders(sub.id, 5).size)
    }

    @Test
    fun `somebody else's subscription cannot be activated and the provider is not called`() {
        val sub = started().subscription

        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.subscriptions.activate("acc_other", sub.id, pgToken) }

        assertEquals(0, kit.gateway.registerCalls.size)
    }

    @Test
    fun `a definite refusal of the first payment ends the subscription, frees the seat and leaves no token`() {
        val sub = started().subscription
        kit.gateway.script(ConfirmScript.Decline("REJECT_CARD_COMPANY"))

        assertFailsWith<PaymentDeclinedException> { kit.subscriptions.activate("acc_1", sub.id, pgToken) }

        val ended = sub(sub.id)
        assertEquals(SubscriptionEndReason.FIRST_PAYMENT_DECLINED, ended.endReason)
        assertNull(ended.billingToken)
        assertNull(ended.activeKey)
        assertNotNull(started("acc_1", "k2").subscription, "the account can try again")
    }

    @Test
    fun `an unknown first payment is not sent again, the lookup settles it by the reserved number, and without a token the renewal ends the subscription without charging`() {
        val sub = started().subscription
        kit.gateway.script(ConfirmScript.TimeoutApplied())

        val pending = kit.subscriptions.activate("acc_1", sub.id, pgToken)
        assertEquals(SubscriptionStatus.PENDING, pending.status)
        assertEquals(1, kit.gateway.registerCalls.size)

        kit.advance(Duration.ofMinutes(5))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE, PaymentTaskTypes.ORDER_FULFIL))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val active = sub(sub.id)
        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertNull(active.billingToken, "the lookup does not carry the token")
        assertEquals(1, kit.gateway.registerCalls.size, "never sent again")
        assertTrue(kit.gateway.lookupCalls.all { it.recurring }, "a subscription order is asked as a recurring payment")

        kit.time.set(checkNotNull(active.currentPeriodEnd))
        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, sub.id, 1, 3))
        assertEquals(0, kit.gateway.chargeCalls.size)
        assertEquals(SubscriptionEndReason.PAYMENT_FAILED, sub(sub.id).endReason)
        // 조용히 끝나지 않는다 - 갱신일에 첫 주문이 운영자에게 표시된다
        assertEquals(PaymentReviewReason.SUBSCRIPTION_TOKEN_MISSING, kit.order(PaymentOrderIds.initialOrderId(sub.id)).reviewReason)
    }

    @Test
    fun `a provider that cannot be asked by order number does not turn a missing key into a refusal - the person decides, the subscription is not charged again`() {
        val sub = started().subscription
        kit.gateway.orderLookupAnswers = false
        kit.gateway.script(ConfirmScript.ServerError) // 모름 - 결제 키는 예약 때 것이 있다
        // 예약 번호를 지워 키 없는 주문을 만든다(응답이 유실된 갱신 과금과 같은 모양)
        kit.subscriptions.activate("acc_1", sub.id, pgToken)
        val order = kit.store.listSubscriptionOrders(sub.id, 1).single()
        kit.store.inTransaction { tx -> tx.updateOrder(tx.lockOrder(order.id)!!.copy(providerPaymentId = null)) }

        repeat(kit.properties.reconcile.maxAttempts) {
            kit.advance(Duration.ofHours(2))
            kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        }

        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason)
        assertEquals(SubscriptionStatus.PENDING, sub(sub.id).status, "no automatic reaction to a null that only means no key")
        assertEquals(1, kit.gateway.registerCalls.size)
    }

    @Test
    fun `an unconfirmed charge that carries the provider's payment number keeps it on the order, and is not sent again`() {
        val plain = PaymentLedgerKit()
        val sub = plain.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription
        plain.subscriptions.activate("acc_1", sub.id, mapOf("authKey" to "a"))
        val active = checkNotNull(plain.store.findSubscription(sub.id))
        plain.gateway.unknownChargeKey = "pay_reserved_1"
        plain.time.set(checkNotNull(active.currentPeriodEnd))

        plain.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, sub.id, 1, 3))
        plain.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, sub.id, 2, 3))

        val renewal = plain.store.listSubscriptionOrders(sub.id, 5).first { it.kind == PaymentOrderKind.SUBSCRIPTION_RENEWAL }
        assertEquals(PaymentOrderStatus.CONFIRMING, renewal.status)
        assertEquals("pay_reserved_1", renewal.providerPaymentId)
        assertEquals(2, plain.gateway.chargeCalls.size, "the first payment and one renewal attempt")
    }

    @Test
    fun `the reserved number is handed back to issue when the provider registers in a separate step`() {
        val separate = PaymentLedgerKit().also { it.gateway.prepareReferences = true }
        val sub = separate.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription

        separate.subscriptions.activate("acc_1", sub.id, mapOf("reserveId" to "ref_1"))

        assertEquals(listOf<String?>("ref_1"), separate.gateway.issueReferences)
    }

    @Test
    fun `a refund of a subscription order asks the provider as a recurring payment, a one-time order does not`() {
        val sub = started().subscription
        kit.subscriptions.activate("acc_1", sub.id, pgToken)
        val order = kit.store.listSubscriptionOrders(sub.id, 1).single()
        val oneTime = kit.paid(key = "o1", paymentKey = "pay_one")

        kit.refunds.refund("op_1", order.id, "OPERATOR", 1000, IdempotencyKey("r1"))
        kit.refunds.refund("op_1", oneTime.id, "OPERATOR", 1000, IdempotencyKey("r2"))

        assertTrue(kit.gateway.refundRequests.first().recurring)
        assertFalse(kit.gateway.refundRequests.last().recurring)
    }
}
