package dev.sumin.skeleton.payment

import dev.sumin.skeleton.crypto.TextEncryptor
import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Random

/** 변경 미리보기 — [charge] 가 지금 결제될 금액이고, [plan] 이 바뀔 플랜. [restartsPeriod] 면 지금부터 새 주기가 시작된다. */
data class PlanChangeQuote(val plan: SubscriptionPlan, val charge: PaymentAmount, val restartsPeriod: Boolean)

/** 변경 때 지금 결제할 금액(스펙 `2026-10-09-subscription-design.md` §9). */
object PlanChangePricing {
    /**
     * [Charge.FULL_RESTART] 는 새 플랜 전액. [Charge.PRORATED_DIFFERENCE] 는 `floor((새 − 옛) × 남은 초 / 기간 전체 초)` 이고 [minCharge] 미만이면 0(과금 없이 바꾼다).
     */
    fun charge(option: Charge, current: PaymentAmount, target: PaymentAmount, periodStart: Instant, periodEnd: Instant, now: Instant, minCharge: Long): Long {
        if (option == Charge.FULL_RESTART) return target.amount
        val total = Duration.between(periodStart, periodEnd).seconds
        if (total <= 0) return 0
        val remaining = Duration.between(now, periodEnd).seconds.coerceIn(0, total)
        val prorated = Math.multiplyExact(target.amount - current.amount, remaining) / total
        return if (prorated < minCharge) 0 else prorated
    }
}

/**
 * 플랜 변경(위로만, 스펙 §9) — 미리보기와 실행. 변경은 주문 한 줄(`SUBSCRIPTION_CHANGE`)이고 **`PAID` 가 된 뒤에만** 플랜이 바뀐다([SubscriptionLifecycle.onOrderPaid]).
 * 그 주문이 구독의 과금 주문 자리([PaymentSubscription.chargeOrderId])를 차지하므로 안 끝난 동안 갱신은 과금하지 않고, 갱신이 과금 중이면 변경은 시작하지 않는다 — 이중 과금이 없다.
 * 결제사 호출은 락 · 트랜잭션 밖이다(바깥 트랜잭션 안에서 부르면 던진다).
 */
class SubscriptionPlanChanges(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val plans: SubscriptionPlans,
    private val encryptor: TextEncryptor,
    private val lifecycle: SubscriptionLifecycle,
    private val charges: SubscriptionCharges,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    private val random: Random = SecureRandom(),
) {
    fun preview(accountId: String, subscriptionId: String, planCode: String): PlanChangeQuote =
        quote(owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException(), planCode)

    /**
     * @param expectedCharge 호출자가 미리보기로 본 금액 — 서버가 다시 계산한 값이 더 크면 [PaymentPlanChangeChargeChangedException].
     * @return 변경된(또는 결제 확인 중이라 [PaymentSubscription.pendingPlanCode] 가 남은) 구독. 결제사가 거절하면 [PaymentDeclinedException] 이고 플랜은 그대로다.
     */
    fun change(accountId: String, subscriptionId: String, planCode: String, expectedCharge: Long, key: IdempotencyKey): PaymentSubscription {
        store.requireNoOpenTransaction("PaymentSubscriptions.change")
        val before = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        val dedupeKey = "subchg:$subscriptionId:${PaymentOrderIds.dedupeKey(accountId, key).take(32)}"
        if (store.findOrderByDedupeKey(dedupeKey) != null) return before // 같은 요청의 재전송 — 이미 했다
        val gateway = billingGateway(before)

        val claim = store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            val quote = quote(locked, planCode)
            if (quote.charge.amount > expectedCharge) throw PaymentPlanChangeChargeChangedException()
            if (quote.charge.amount == 0L) {
                // 차액이 최소 금액 미만이다 — 과금 없이 바꾼다
                return@inTransaction Claim(lifecycle.save(tx, locked, locked.status) { it.copy(planCode = quote.plan.code, amount = quote.plan.amount) }, null)
            }
            val order = lifecycle.chargeOrder(locked, PaymentOrderKind.SUBSCRIPTION_CHANGE, quote.plan.name, random, quote.charge, quote.plan.code, dedupeKey)
            if (!tx.insertOrderIfAbsent(order)) return@inTransaction Claim(locked, null) // 같은 키로 동시에 들어온 요청이 먼저 넣었다
            lifecycle.chargeStarted(tx, order, PaymentActor.USER, accountId)
            lifecycle.scheduleReconcile(order)
            val claimed = locked.copy(chargeOrderId = order.id, pendingPlanCode = quote.plan.code, pendingAmount = quote.plan.amount.amount, updatedAt = writer.now())
            tx.updateSubscription(claimed)
            Claim(claimed, order)
        }
        val order = claim.order ?: return claim.subscription

        val token = encryptor.decrypt(checkNotNull(claim.subscription.billingToken) { "a live subscription has a billing key" })
        val outcome = charges.charge(gateway, claim.subscription, order, token, PaymentActor.USER)
        if (outcome is ChargeOutcome.Declined) throw PaymentDeclinedException(outcome.providerCode, gateway.billingDeclineCategory(outcome.providerCode))
        return store.findSubscription(subscriptionId) ?: claim.subscription
    }

    private class Claim(val subscription: PaymentSubscription, val order: PaymentOrder?)

    private fun quote(subscription: PaymentSubscription, planCode: String): PlanChangeQuote {
        val option = properties.subscription.planChange.takeIf { it.enabled }?.charge ?: throw PaymentPlanChangeNotAllowedException()
        if (subscription.status != SubscriptionStatus.ACTIVE) throw PaymentPlanChangeNotAllowedException()
        val target = plans.find(planCode)?.takeIf { it.amount.amount > 0 } ?: throw PaymentItemNotPurchasableException()
        val current = plans.find(subscription.planCode) ?: throw PaymentPlanChangeNotAllowedException()
        if (!isUpgrade(current, target, subscription)) throw PaymentPlanChangeNotAllowedException()
        // 과금 중인(안 끝난) 주문이 있으면 시작하지 않는다 — 갱신이 나가 있거나 앞선 변경이 확인 중이다
        if (!lifecycle.isSettled(subscription.chargeOrderId?.let(store::findOrder))) throw PaymentPlanChangeNotAllowedException()
        val start = subscription.currentPeriodStart ?: throw PaymentPlanChangeNotAllowedException()
        val end = subscription.currentPeriodEnd ?: throw PaymentPlanChangeNotAllowedException()
        val charge = PlanChangePricing.charge(option, subscription.amount, target.amount, start, end, writer.now(), properties.subscription.planChange.minCharge)
        return PlanChangeQuote(target, PaymentAmount(charge, target.amount.currency), restartsPeriod = option == Charge.FULL_RESTART)
    }

    /**
     * 「위로 바꾸기」인가 — **이 판정은 여기 한 곳**이다. 지금은 같은 주기 · 같은 통화의 윗등급만 허용한다(주기가 다른 플랜, 예: 월 → 연 은 거절).
     * 연간 플랜 설계가 정해지면 이 함수만 바꾼다.
     */
    private fun isUpgrade(current: SubscriptionPlan, target: SubscriptionPlan, subscription: PaymentSubscription): Boolean =
        target.level > current.level && target.period == subscription.period && target.amount.currency == subscription.amount.currency

    private fun billingGateway(subscription: PaymentSubscription): PaymentBillingGateway =
        try {
            router.gateway(subscription.provider, PaymentBillingGateway::class).also { if (it.mode != subscription.providerMode) throw PaymentModeMismatchException() }
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentUnavailableException()
        }

    private fun owned(subscription: PaymentSubscription?, accountId: String): PaymentSubscription? = subscription?.takeIf { it.accountId == accountId }
}
