package dev.sumin.skeleton.payment

import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import dev.sumin.skeleton.payment.testing.PaymentLedgerKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 지금 살 수 없는 가격(판매 중지 · 기간 지남)으로는 새로 시작하지도 갈아타지도 못하고, 그 가격의 기존 구독은 갱신 · 이름이 그대로다. 메모리 저장소 + 빌링 대역. */
class SubscriptionPlanPurchasableTest {
    private fun newKit() = PaymentLedgerKit(
        properties = PaymentProperties(
            webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN),
            subscription = PaymentProperties.Subscription(enabled = true, planChange = PaymentProperties.Subscription.PlanChange(true, Charge.PRORATED_DIFFERENCE, 0)),
        ),
    )

    private fun PaymentLedgerKit.stop(code: String) {
        plans.plans.replaceAll { if (it.code == code) it.copy(purchasable = false) else it }
    }

    @Test
    fun `a plan that is no longer purchasable cannot be started - nothing reaches the provider and no row is made`() {
        val kit = newKit()
        kit.stop("basic")

        assertFailsWith<PaymentItemNotPurchasableException> { kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")) }

        assertEquals(null, kit.store.findLatestSubscription("acc_1"))
        assertEquals(0, kit.gateway.chargeCalls.size)
    }

    @Test
    fun `a subscriber of a stopped price keeps the name, the renewal and the amount`() {
        val kit = newKit()
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        val active = kit.subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
        kit.stop("basic")

        assertEquals("Basic", kit.plans.find("basic")?.name, "find still answers for the stopped price")
        kit.time.set(checkNotNull(active.currentPeriodEnd))
        kit.runTasks(setOf(PaymentTaskTypes.SUBSCRIPTION_RENEW))

        assertEquals(3_900, kit.gateway.chargeCalls.last().amount.amount)
        assertEquals(2, checkNotNull(kit.store.findSubscription(id)).periodSeq)
    }

    @Test
    fun `a plan change to a plan that is not purchasable is refused`() {
        val kit = newKit()
        val id = kit.subscriptions.start("acc_1", "basic", IdempotencyKey("k1")).subscription.id
        kit.subscriptions.activate("acc_1", id, mapOf("authKey" to "auth_1"))
        kit.time.advance(Duration.ofDays(1))
        kit.stop("plus")
        val charges = kit.gateway.chargeCalls.size

        assertFailsWith<PaymentItemNotPurchasableException> { kit.planChanges.preview("acc_1", id, "plus") }
        assertFailsWith<PaymentItemNotPurchasableException> { kit.planChanges.change("acc_1", id, "plus", 9_999, IdempotencyKey("chg-1")) }

        assertEquals("basic", checkNotNull(kit.store.findSubscription(id)).planCode)
        assertEquals(charges, kit.gateway.chargeCalls.size)
    }
}

/** 이 요청 통화로 플랜을 거른다 - 그 통화 플랜이 없거나 통화를 모르면 거르지 않는다(통화 하나짜리 코드 플랜 앱은 그대로). */
class PlansForCurrencyTest {
    private val kit = PaymentLedgerKit(properties = PaymentProperties(webhook = PaymentProperties.Webhook(pathToken = PaymentLedgerKit.WEBHOOK_TOKEN), subscription = PaymentProperties.Subscription(enabled = true))).also {
        it.plans.plans += SubscriptionPlan("basic-usd", "Basic", PaymentAmount(300, "USD"), "P1M", 1, productCode = "basic")
    }

    @Test
    fun `only the plans of the request currency are listed, and all of them when there is none of that currency or the currency is unknown`() {
        assertEquals(listOf("basic", "plus"), kit.subscriptions.plansFor("KRW").map { it.code })
        assertEquals(listOf("basic-usd"), kit.subscriptions.plansFor("USD").map { it.code })
        assertEquals(listOf("basic", "plus", "basic-usd"), kit.subscriptions.plansFor("JPY").map { it.code }, "no plan in that currency - nothing is filtered")
        assertEquals(listOf("basic", "plus", "basic-usd"), kit.subscriptions.plansFor(null).map { it.code })
    }
}
