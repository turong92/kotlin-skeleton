package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import dev.sumin.skeleton.payment.testing.ScriptedGateway.ConfirmScript
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 갱신에서 깨지면 사고 나는 것: 이중 과금(두 워커 · 모름 뒤 재전송) · 해지 · 탈퇴 · 정지 뒤 과금 · 늦은 결제가 기간을 넘기는 것 · 재시도 끝의 종료. 메모리 저장소 + 빌링 대역. */
class SubscriptionRenewalTest {
    private val kit = PaymentLedgerKit()
    private val auth = mapOf("authKey" to "auth_1")
    private val month = Duration.ofDays(31)

    private fun activeSubscription(account: String = "acc_1", key: String = "k1"): PaymentSubscription {
        val id = kit.subscriptions.start(account, "basic", IdempotencyKey(key)).subscription.id
        return kit.subscriptions.activate(account, id, auth).also { assertEquals(SubscriptionStatus.ACTIVE, it.status) }
    }

    private fun sub(id: String) = checkNotNull(kit.store.findSubscription(id))

    private fun renewTasks() = kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))

    private fun toPeriodEnd(sub: PaymentSubscription) = kit.time.set(checkNotNull(sub.currentPeriodEnd))

    @Test
    fun `at the end of the period one renewal is charged with the stored amount and the next period counts from the first payment, not from today`() {
        val active = activeSubscription()
        val anchor = checkNotNull(active.anchorAt)
        kit.time.set(checkNotNull(active.currentPeriodEnd).plus(Duration.ofDays(2))) // 이틀 늦게 깬 작업

        renewTasks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val renewed = sub(active.id)
        assertEquals(2, kit.gateway.chargeCalls.size)
        assertEquals(2, renewed.periodSeq)
        assertEquals(SubscriptionStatus.ACTIVE, renewed.status)
        assertEquals(SubscriptionPeriod.parse("P1M").after(anchor, 2), renewed.currentPeriodEnd, "the end is first payment + 2 months - no drift from the late wake-up")
        val orders = kit.store.listSubscriptionOrders(active.id, 5)
        assertEquals(listOf(PaymentOrderKind.SUBSCRIPTION_RENEWAL, PaymentOrderKind.SUBSCRIPTION_INITIAL), orders.map { it.kind })
        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals("bkey_1", kit.gateway.chargeCalls.last().token)
    }

    @Test
    fun `a price change in the catalog does not touch an existing subscription - the renewal charges the amount the row holds`() {
        val active = activeSubscription()
        kit.plans.plans[0] = kit.plans.plans[0].copy(amount = PaymentAmount(9_900, "KRW"))
        toPeriodEnd(active)

        renewTasks()

        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount)
    }

    @Test
    fun `two workers that pick the same renewal leave one order and one provider call`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        val bothInside = CountDownLatch(2)
        kit.gateway.duringCharge = {
            // 첫 호출이 승인하는 동안 다른 워커가 같은 갱신을 집는다
        }
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                pool.submit<Result<Unit>> {
                    start.await()
                    bothInside.countDown()
                    runCatching { kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, active.id, 1, 12)) }
                }
            }
            start.countDown()
            results.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(2, kit.gateway.chargeCalls.size, "the first payment and exactly one renewal")
        assertEquals(2, kit.store.listSubscriptionOrders(active.id, 10).size)
        assertEquals(2, sub(active.id).periodSeq)
    }

    @Test
    fun `an unknown answer to a renewal is not sent again - the lookup finishes it and the period moves once`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.TimeoutApplied())

        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, active.id, 1, 12))
        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, active.id, 2, 12))
        assertEquals(2, kit.gateway.chargeCalls.size, "the second run saw the charge in flight and did not send again")

        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))

        assertEquals(2, kit.gateway.chargeCalls.size)
        assertEquals(2, sub(active.id).periodSeq)
        assertEquals(1, kit.gateway.providerPaymentsOfOrder(kit.store.listSubscriptionOrders(active.id, 1).single().id).size)
    }

    @Test
    fun `a renewal order that is paid late while the subscription waits for another charge does not move the period - it is flagged for a person`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, active.id, 1, 12))
        val first = kit.store.listSubscriptionOrders(active.id, 1).single()
        // 결제사가 그 결제를 실패로 닫는다 → 첫 시도 실패 → 두 번째 시도가 성공한다
        kit.gateway.abort(kit.gateway.providerPaymentsOfOrder(first.id).single().paymentKey)
        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        assertEquals(SubscriptionStatus.PAST_DUE, sub(active.id).status, "the failed order made it past due in the same transaction")
        kit.advance(kit.properties.subscription.renewal.retryInterval)
        renewTasks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))
        val before = sub(active.id)
        assertEquals(SubscriptionStatus.ACTIVE, before.status)
        assertEquals(2, before.periodSeq)

        // 이제 첫 시도의 결제가 늦게 도착했다
        val paymentKey = kit.gateway.providerPaymentsOfOrder(first.id).single().paymentKey
        kit.gateway.settle(paymentKey)
        val snapshot = checkNotNull(kit.gateway.lookup(PaymentLookupQuery(paymentKey, null)))
        kit.store.inTransaction { tx ->
            kit.convergence.converge(tx, checkNotNull(tx.lockOrder(first.id)), snapshot, ConvergeContext(PaymentMode.TEST, ConvergeSource.SYNC))
        }
        kit.fulfillment.fulfil(first.id)

        val after = sub(active.id)
        assertEquals(before.periodSeq, after.periodSeq, "the period is moved by the charge the subscription waits for, not by a late order")
        assertEquals(before.currentPeriodEnd, after.currentPeriodEnd)
        assertEquals(PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, kit.order(first.id).reviewReason)
        assertTrue(kit.review.flagged.any { it.first == first.id })
    }

    @Test
    fun `a declined renewal makes it past due and retries, the retry that works brings it back, and the period still counts from the first payment`() {
        val active = activeSubscription()
        val anchor = checkNotNull(active.anchorAt)
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.Decline("REJECT_CARD_COMPANY"))

        renewTasks()

        val pastDue = sub(active.id)
        assertEquals(SubscriptionStatus.PAST_DUE, pastDue.status)
        assertEquals(2, pastDue.attempt)
        assertEquals(false, pastDue.status.isInUse, "a past-due subscription is not usable")

        kit.advance(kit.properties.subscription.renewal.retryInterval)
        renewTasks()
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_FULFIL))

        val back = sub(active.id)
        assertEquals(SubscriptionStatus.ACTIVE, back.status)
        assertEquals(1, back.attempt)
        assertEquals(2, back.periodSeq)
        assertEquals(SubscriptionPeriod.parse("P1M").after(anchor, 2), back.currentPeriodEnd)
        assertEquals(3, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `when every attempt is declined the subscription ends, the seat is free and the billing key is removed`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        repeat(kit.properties.subscription.renewal.maxAttempts) {
            kit.gateway.script(ConfirmScript.Decline())
            renewTasks()
            kit.advance(kit.properties.subscription.renewal.retryInterval)
        }

        val ended = sub(active.id)
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.PAYMENT_FAILED, ended.endReason)
        assertNull(ended.activeKey)
        assertEquals(1 + kit.properties.subscription.renewal.maxAttempts, kit.gateway.chargeCalls.size)
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_REVOKE))
        assertEquals("bkey_1", kit.gateway.revokeCalls.single().second)
        assertNull(sub(active.id).billingToken)
        // 지금 더 깨워도 과금은 없다
        val calls = kit.gateway.chargeCalls.size
        renewTasks()
        assertEquals(calls, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `canceling a past-due subscription ends it at once`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.Decline())
        renewTasks()

        val ended = kit.subscriptions.cancel("acc_1", active.id)

        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.USER_CANCELED, ended.endReason)
    }

    @Test
    fun `a canceled subscription is not charged at the end of the period - it ends`() {
        val active = activeSubscription()
        kit.subscriptions.cancel("acc_1", active.id)
        toPeriodEnd(active)

        renewTasks()

        val ended = sub(active.id)
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.USER_CANCELED, ended.endReason)
        assertEquals(1, kit.gateway.chargeCalls.size, "only the first payment was ever charged")
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_REVOKE))
        assertEquals(1, kit.gateway.revokeCalls.size)
    }

    @Test
    fun `a canceled subscription stays usable until the period ends and is not charged before`() {
        val active = activeSubscription()
        kit.subscriptions.cancel("acc_1", active.id)
        kit.advance(Duration.ofDays(1))

        renewTasks()

        assertEquals(SubscriptionStatus.CANCELING, sub(active.id).status)
        assertEquals(1, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `an account that left is not charged again - the subscription is closed, the card forgotten and a renewal that wakes up does nothing`() {
        val active = activeSubscription()

        kit.erasure.erase(ErasureRequest("acc_1", "erased-tombstone"))
        toPeriodEnd(active)
        renewTasks()

        val ended = sub(active.id)
        assertEquals(SubscriptionStatus.ENDED, ended.status)
        assertEquals(SubscriptionEndReason.ACCOUNT_ERASED, ended.endReason)
        assertNull(ended.cardLast4)
        assertNotNull(ended.accountErasedAt)
        assertEquals(1, kit.gateway.chargeCalls.size)
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_REVOKE))
        assertEquals("bkey_1", kit.gateway.revokeCalls.single().second)
        assertNull(sub(active.id).billingToken)
        // 두 번 지워도 같다
        kit.erasure.erase(ErasureRequest("acc_1", "erased-tombstone"))
        assertEquals(1, kit.gateway.revokeCalls.size)
    }

    @Test
    fun `the app's guard stops the renewal of a suspended account and the subscription ends at the period end`() {
        val active = activeSubscription()
        kit.blocked += "acc_1"
        toPeriodEnd(active)

        renewTasks()

        assertEquals(SubscriptionStatus.ENDED, sub(active.id).status)
        assertEquals(SubscriptionEndReason.RENEWAL_BLOCKED, sub(active.id).endReason)
        assertEquals(1, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `the whole period refunded ends the subscription so the refund is not followed by another charge`() {
        val active = activeSubscription()
        val order = kit.store.listSubscriptionOrders(active.id, 1).single()

        kit.refunds.refund("operator_1", order.id, "OPERATOR", null, IdempotencyKey("refund-1"))
        toPeriodEnd(active)
        renewTasks()

        assertEquals(SubscriptionStatus.ENDED, sub(active.id).status)
        assertEquals(SubscriptionEndReason.REFUNDED, sub(active.id).endReason)
        assertEquals(1, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `a first charge that was unknown and then closed as failed frees the seat through the renewal task`() {
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        kit.gateway.script(ConfirmScript.TimeoutNotApplied)
        assertEquals(SubscriptionStatus.PENDING, kit.subscriptions.activate("acc_1", id, auth).status)
        val order = kit.store.listSubscriptionOrders(id, 1).single()
        kit.gateway.abort(kit.gateway.providerPaymentsOfOrder(order.id).single().paymentKey)
        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))

        assertEquals(SubscriptionStatus.ENDED, sub(id).status, "the order closing freed the seat in the same transaction")
        assertEquals(SubscriptionEndReason.FIRST_PAYMENT_DECLINED, sub(id).endReason)
        assertNull(sub(id).activeKey)
        assertNotNull(kit.subscriptions.start("acc_1", "basic", IdempotencyKey("again")).subscription)
    }

    /** 조회가 끝내 null 인 채(결제사가 그 결제를 모른다) 조회 작업이 시도를 다 써 죽는다. */
    private fun reconcileUntilDead(orderId: String) {
        repeat(kit.properties.reconcile.maxAttempts) {
            kit.advance(Duration.ofHours(2))
            kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        }
        assertTrue(kit.tasks.dead(PaymentTaskTypes.ORDER_RECONCILE).any { it.task.subjectId == orderId }, "the lookup task died")
    }

    @Test
    fun `a first charge the provider never knew - lookup null to the end - frees the account instead of locking it`() {
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        kit.gateway.script(ConfirmScript.ServerError) // 요청이 닿지 않았다 — 결제사에 그 결제가 없다
        assertEquals(SubscriptionStatus.PENDING, kit.subscriptions.activate("acc_1", id, auth).status)
        val order = kit.store.listSubscriptionOrders(id, 1).single()

        reconcileUntilDead(order.id)

        assertEquals(SubscriptionStatus.ENDED, sub(id).status)
        assertEquals(SubscriptionEndReason.FIRST_PAYMENT_DECLINED, sub(id).endReason)
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason, "a person still sees the order")
        assertNotNull(kit.subscriptions.start("acc_1", "basic", IdempotencyKey("again")))
    }

    @Test
    fun `a renewal the provider never knew goes past due and retries instead of waiting for ever`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.ServerError)
        renewTasks()
        val order = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(PaymentOrderStatus.CONFIRMING, order.status)

        reconcileUntilDead(order.id)

        assertEquals(SubscriptionStatus.PAST_DUE, sub(active.id).status)
        assertEquals(2, sub(active.id).attempt)
    }

    @Test
    fun `a renewal whose provider is gone or in another mode leaves no order behind`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.mode = PaymentMode.LIVE

        assertFailsWith<PaymentTaskPermanentException> { kit.renew.handle(PaymentTaskRun(PaymentTaskTypes.SUBSCRIPTION_RENEW, active.id, 1, 12)) }

        assertEquals(1, kit.store.listSubscriptionOrders(active.id, 5).size, "only the first payment's order")
        assertEquals(1, kit.gateway.chargeCalls.size)
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
    }

    @Test
    fun `a cancel after the renewal was claimed and then a decline ends the subscription - the order closes and nothing throws`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.duringCharge = {
            kit.gateway.duringCharge = null
            kit.subscriptions.cancel("acc_1", active.id)
        }
        kit.gateway.script(ConfirmScript.Decline())

        renewTasks()

        val renewal = kit.store.listSubscriptionOrders(active.id, 1).single()
        assertEquals(PaymentOrderStatus.FAILED, renewal.status)
        assertEquals(SubscriptionStatus.ENDED, sub(active.id).status)
        assertEquals(SubscriptionEndReason.USER_CANCELED, sub(active.id).endReason)
    }

    @Test
    fun `a renewal whose lookup throws to the end is left for a person - no past due, no second order`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.ServerError)
        renewTasks()
        val order = kit.store.listSubscriptionOrders(active.id, 1).single()
        kit.gateway.failLookups(100)

        reconcileUntilDead(order.id)
        kit.advance(kit.properties.subscription.renewal.retryInterval.multipliedBy(2))
        renewTasks()

        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertEquals(1, sub(active.id).attempt)
        assertEquals(2, kit.store.listSubscriptionOrders(active.id, 5).size, "no new order while a person has not looked")
        assertEquals(2, kit.gateway.chargeCalls.size)
        assertEquals(PaymentReviewReason.CONFIRM_UNRESOLVED, kit.order(order.id).reviewReason)
    }

    @Test
    fun `a renewal that dies on an amount mismatch is left for a person - the subscription does not retry`() {
        val active = activeSubscription()
        toPeriodEnd(active)
        kit.gateway.script(ConfirmScript.TimeoutApplied(PaymentAmount(1_000, "KRW")))
        renewTasks()
        val order = kit.store.listSubscriptionOrders(active.id, 1).single()
        kit.advance(kit.properties.reconcile.firstDelay)
        kit.runTasks(setOf(PaymentTaskTypes.ORDER_RECONCILE))
        kit.advance(kit.properties.subscription.renewal.retryInterval.multipliedBy(2))
        renewTasks()

        assertEquals(PaymentReviewReason.AMOUNT_MISMATCH, kit.order(order.id).reviewReason)
        assertEquals(SubscriptionStatus.ACTIVE, sub(active.id).status)
        assertEquals(2, kit.store.listSubscriptionOrders(active.id, 5).size)
        assertEquals(2, kit.gateway.chargeCalls.size)
    }
}
