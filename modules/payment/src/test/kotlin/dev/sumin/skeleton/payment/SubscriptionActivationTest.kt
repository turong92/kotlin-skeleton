package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript

/** 구독 시작(카드 등록 + 첫 결제)에서 깨지면 사고 나는 것: 이중 과금 · 남의 구독 접근 · 빌링키 노출 · 해지. 서비스 논리는 메모리 저장소 + 빌링 대역으로. */
class SubscriptionActivationTest {
    private val kit = PaymentLedgerKit()
    private val auth = mapOf("authKey" to "auth_1")

    private fun started(account: String = "acc_1", key: String = "k1", plan: String = "basic") = kit.subscriptions.start(account, plan, IdempotencyKey(key))

    @Test
    fun `a first payment turns the subscription active, charges once and keeps the billing key encrypted`() {
        val sub = started().subscription

        val active = kit.subscriptions.activate("acc_1", sub.id, auth)

        assertEquals(SubscriptionStatus.ACTIVE, active.status)
        assertEquals(1, kit.gateway.chargeCalls.size)
        assertEquals(1, active.periodSeq)
        assertEquals(active.anchorAt?.let { kit.writer.now() }, kit.writer.now().takeIf { active.anchorAt != null })
        assertNotNull(active.currentPeriodEnd)
        val order = kit.store.listSubscriptionOrders(sub.id, 5).single()
        assertEquals(PaymentOrderKind.SUBSCRIPTION_INITIAL, order.kind)
        assertEquals(PaymentOrderStatus.PAID, order.status)
        assertEquals(sub.id, order.originRef)
        // 빌링키는 암호문으로만 저장된다
        val stored = checkNotNull(kit.store.findSubscription(sub.id)?.billingToken)
        assertNotEquals("bkey_1", stored)
        assertEquals("bkey_1", ReversibleTestEncryptor.decrypt(stored))
        // 결제사에는 평문으로 갔다
        assertEquals("bkey_1", kit.gateway.chargeCalls.single().token)
        assertEquals(order.id, kit.gateway.chargeCalls.single().idempotencyKey.value)
    }

    @Test
    fun `the billing key and the customer key are in no toString the service hands out`() {
        val sub = started().subscription
        val active = kit.subscriptions.activate("acc_1", sub.id, auth)
        val order = kit.store.listSubscriptionOrders(sub.id, 5).single()
        val texts = listOf(active.toString(), sub.toString(), order.toString(), kit.gateway.chargeCalls.single().toString(), kit.gateway.issueCalls.toString().takeIf { false } ?: "")
        val secrets = listOf("bkey_1", ReversibleTestEncryptor.encrypt("bkey_1"), checkNotNull(active.customerKey), "1234")

        texts.forEach { text -> secrets.forEach { secret -> assertFalse(text.contains(secret), "$secret leaked into $text") } }
    }

    @Test
    fun `two activations of the same subscription charge once and the loser's fresh billing key is removed`() {
        val sub = started().subscription
        val bothIssued = CountDownLatch(2)
        kit.gateway.duringIssue = {
            bothIssued.countDown()
            bothIssued.await(5, TimeUnit.SECONDS)
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { pool.submit<PaymentSubscription> { kit.subscriptions.activate("acc_1", sub.id, auth) } }.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, kit.gateway.chargeCalls.size, "a second activation must not charge again")
            assertTrue(results.all { it.status == SubscriptionStatus.ACTIVE || it.status == SubscriptionStatus.PENDING })
            assertEquals(1, kit.store.listSubscriptionOrders(sub.id, 5).size)
            assertEquals(1, kit.gateway.revokeCalls.size, "the key the loser got must not stay alive at the provider")
            val kept = ReversibleTestEncryptor.decrypt(checkNotNull(kit.store.findSubscription(sub.id)?.billingToken))
            assertNotEquals(kept, kit.gateway.revokeCalls.single().second, "the winner's key must survive")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an account holds one live subscription - the second start is refused and a second activation loses its fresh billing key`() {
        val first = started(key = "k1").subscription
        val second = started(key = "k2").subscription // 둘 다 PENDING 이다 — 아직 자리를 안 잡았다
        kit.subscriptions.activate("acc_1", first.id, auth)

        assertFailsWith<PaymentAlreadySubscribedException> { kit.subscriptions.activate("acc_1", second.id, auth) }
        assertFailsWith<PaymentAlreadySubscribedException> { started(key = "k3") }

        assertEquals(1, kit.gateway.chargeCalls.size)
        assertEquals(1, kit.gateway.revokeCalls.size)
        assertEquals(SubscriptionStatus.PENDING, kit.store.findSubscription(second.id)?.status)
    }

    @Test
    fun `the same start request is the same subscription and another plan under the same key is a conflict`() {
        val first = started()
        val again = started()

        assertEquals(first.subscription.id, again.subscription.id)
        assertTrue(again.replayed)
        assertEquals(first.subscription.customerKey, again.billing?.customerKey)
        assertFailsWith<PaymentIdempotencyConflictException> { started(plan = "plus") }
        assertFailsWith<PaymentItemNotPurchasableException> { started(key = "other", plan = "gold") }
    }

    @Test
    fun `somebody else's subscription is as missing as one that does not exist`() {
        val sub = started().subscription

        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.subscriptions.activate("acc_2", sub.id, auth) }
        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.subscriptions.cancel("acc_2", sub.id) }
        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.subscriptions.activate("acc_1", "sub_missing", auth) }
        assertFailsWith<PaymentSubscriptionNotFoundException> { kit.subscriptions.cancel("acc_1", "sub_missing") }
        assertTrue(kit.gateway.issueCalls.isEmpty() && kit.gateway.chargeCalls.isEmpty())
        assertNull(kit.subscriptions.current("acc_2"))
    }

    @Test
    fun `a declined first payment ends the subscription, frees the seat and removes the billing key`() {
        val sub = started().subscription
        kit.gateway.script(ConfirmScript.Decline("REJECT_CARD_COMPANY"))

        val declined = assertFailsWith<PaymentDeclinedException> { kit.subscriptions.activate("acc_1", sub.id, auth) }

        assertEquals("REJECT_CARD_COMPANY", declined.providerCode)
        val ended = checkNotNull(kit.store.findSubscription(sub.id))
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.FIRST_PAYMENT_DECLINED, ended.endReason)
        assertNull(ended.activeKey)
        assertNull(ended.cardLast4)
        assertEquals(PaymentOrderStatus.FAILED, kit.store.listSubscriptionOrders(sub.id, 5).single().status)
        // 빌링키는 결제사에서 지운 뒤에야 줄에서 사라진다
        assertNotNull(ended.billingToken)
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_REVOKE))
        assertEquals("bkey_1", kit.gateway.revokeCalls.single().second)
        assertNull(kit.store.findSubscription(sub.id)?.billingToken)
        assertNull(kit.store.findSubscription(sub.id)?.customerKey)
        // 자리가 풀렸으니 다시 구독할 수 있다
        assertNotNull(started(key = "again").subscription)
    }

    @Test
    fun `an unknown answer to the first charge is not sent again - the lookup finishes it and the subscription turns active`() {
        val sub = started().subscription
        kit.gateway.script(ConfirmScript.TimeoutApplied())

        val pending = kit.subscriptions.activate("acc_1", sub.id, auth)

        assertEquals(SubscriptionStatus.PENDING, pending.status)
        assertEquals(1, kit.gateway.chargeCalls.size)
        // 다시 눌러도 결제사를 다시 부르지 않는다
        assertEquals(SubscriptionStatus.PENDING, kit.subscriptions.activate("acc_1", sub.id, auth).status)
        assertEquals(1, kit.gateway.chargeCalls.size)
        assertEquals(1, kit.gateway.issueCalls.size)

        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        assertEquals(SubscriptionStatus.ACTIVE, kit.store.findSubscription(sub.id)?.status)
        assertEquals(1, kit.gateway.chargeCalls.size)
        assertEquals(1, kit.gateway.providerPaymentsOfOrder(kit.store.listSubscriptionOrders(sub.id, 5).single().id).size)
    }

    @Test
    fun `canceling reserves the end once and answers the same when pressed again`() {
        val sub = started().subscription
        kit.subscriptions.activate("acc_1", sub.id, auth)

        val canceling = kit.subscriptions.cancel("acc_1", sub.id)
        val again = kit.subscriptions.cancel("acc_1", sub.id)

        assertEquals(SubscriptionStatus.CANCELING, canceling.status)
        assertEquals(canceling, again)
        assertTrue(canceling.status.isInUse)
        assertEquals(canceling.currentPeriodEnd, kit.subscriptions.current("acc_1")?.currentPeriodEnd)
        assertEquals(1, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `a subscription that was never started cannot be canceled`() {
        val sub = started().subscription

        assertFailsWith<PaymentSubscriptionNotCancelableException> { kit.subscriptions.cancel("acc_1", sub.id) }
    }
}
