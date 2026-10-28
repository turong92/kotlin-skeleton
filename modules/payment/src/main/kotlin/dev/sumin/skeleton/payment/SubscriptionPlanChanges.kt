package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.crypto.TextEncryptor
import dev.sumin.skeleton.payment.PaymentProperties.Subscription.PlanChange.Charge
import java.time.Instant

/**
 * 변경 미리보기 — [charge] 가 지금 결제될 금액이고, [plan] 이 바뀔 플랜. [restartsPeriod] 면 지금부터 새 주기가 시작된다.
 * [kind] 가 [PlanChangeKind.SCHEDULED] 면 지금은 결제 없이(0) 예약만 하고 [effectiveAt](= 이번 기간 끝)에 바뀐다. 바로 바뀌는 변경은 [effectiveAt] 이 지금이다.
 */
data class PlanChangeQuote(
    val plan: SubscriptionPlan,
    val charge: PaymentAmount,
    val restartsPeriod: Boolean,
    val kind: PlanChangeKind = PlanChangeKind.IMMEDIATE_SAME_PERIOD,
    val effectiveAt: Instant? = null,
)

/** 변경 때 지금 결제할 금액(스펙 `2026-10-09-subscription-design.md` §9). */
object PlanChangePricing {
    /**
     * [Charge.FULL_RESTART] 는 새 플랜 전액. [Charge.PRORATED_DIFFERENCE] 는 `floor((새 − 옛) × 남은 일수 / 기간 일수)`([Proration] - 날짜 단위) 이고 [minCharge] 미만이면 0(과금 없이 바꾼다).
     */
    fun charge(
        option: Charge,
        current: PaymentAmount,
        target: PaymentAmount,
        periodStart: Instant,
        periodEnd: Instant,
        now: Instant,
        minCharge: Long,
        zone: java.time.ZoneId = Proration.DEFAULT_ZONE,
    ): Long {
        if (option == Charge.FULL_RESTART) return target.amount
        val prorated = Proration.remaining(target.amount - current.amount, periodStart, periodEnd, now, zone)
        return if (prorated < minCharge) 0 else prorated
    }
}

/**
 * 플랜 변경(스펙 `2026-10-29-catalog-pricing-design.md` §5 · `2026-10-09-subscription-design.md` §9) — 미리보기와 실행. 종류는 [PlanChangeRules.classify] 한 곳이 정한다:
 * 같은 주기의 윗등급 · 더 긴 주기로(월 → 연)는 바로, 등급 내리기 · 주기 줄이기는 다음 갱신 때 적용하는 **예약**이다(결제 없이 구독 줄에 적어 두고, 갱신 작업이 같은 트랜잭션에서 적용한다).
 * 아래는 「바로」 갈아타기의 이야기다.
 * 변경은 주문 한 줄(`SUBSCRIPTION_CHANGE`)이고 **`PAID` 가 된 뒤에만** 플랜이 바뀐다([SubscriptionLifecycle.onOrderPaid]).
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
    private val ids: SortableIds = SortableIds.system,
) {
    private val currentPayments = CurrentPeriodPayments(store)

    fun preview(accountId: String, subscriptionId: String, planCode: String): PlanChangeQuote =
        quote(owned(store.findSubscription(subscriptionId), accountId)?.also { if (it.isProviderManaged) throw PaymentPlanChangeNotAllowedException() } ?: throw PaymentSubscriptionNotFoundException(), planCode)

    /**
     * @param expectedCharge 호출자가 미리보기로 본 금액 — 서버가 다시 계산한 값이 더 크면 [PaymentPlanChangeChargeChangedException].
     * @return 변경된(또는 결제 확인 중이라 [PaymentSubscription.pendingPlanCode] 가 남은) 구독. 결제사가 거절하면 [PaymentDeclinedException] 이고 플랜은 그대로다.
     */
    fun change(accountId: String, subscriptionId: String, planCode: String, expectedCharge: Long, key: IdempotencyKey): PaymentSubscription {
        store.requireNoOpenTransaction("PaymentSubscriptions.change")
        val before = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        if (before.isProviderManaged) throw PaymentPlanChangeNotAllowedException() // 결제사가 갱신하는 구독의 플랜 변경은 2차
        val dedupeKey = "subchg:$subscriptionId:${PaymentOrderIds.dedupeKey(accountId, key).take(32)}"
        if (store.findOrderByDedupeKey(dedupeKey) != null) return before // 같은 요청의 재전송 — 이미 했다
        val gateway = billingGateway(before)

        val claim = store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            val quote = quote(locked, planCode)
            if (quote.charge.amount > expectedCharge) throw PaymentPlanChangeChargeChangedException()
            if (quote.kind == PlanChangeKind.SCHEDULED) {
                // 다음 갱신부터 — 결제 없이 적어 둔다(예약을 다시 하면 덮어쓴다). 금액은 지금 값이 박혀 그 사이 가격표가 바뀌어도 이 값으로 과금한다
                return@inTransaction Claim(lifecycle.save(tx, locked, locked.status) { it.copy(scheduledPlanCode = quote.plan.code, scheduledAmount = quote.plan.amount.amount, scheduledPeriod = quote.plan.period) }, null)
            }
            val newPeriod = quote.kind == PlanChangeKind.IMMEDIATE_NEW_PERIOD
            if (quote.charge.amount == 0L) {
                // 차액이 최소 금액 미만이다(또는 낸 돈의 남은 몫이 새 가격을 덮는다) — 과금 없이 바꾼다. 주기를 늘리는 변경은 오늘부터 새 주기
                val changed = lifecycle.save(tx, locked, locked.status) { lifecycle.switched(it, quote.plan.code, quote.plan.amount.amount, if (newPeriod) quote.plan.period else it.period, restartAt = if (newPeriod) writer.now() else null) }
                if (newPeriod) lifecycle.scheduleRenewal(changed)
                return@inTransaction Claim(changed, null)
            }
            val order = lifecycle.chargeOrder(locked, PaymentOrderKind.SUBSCRIPTION_CHANGE, quote.plan.name, ids, quote.charge, quote.plan.code, dedupeKey)
            if (!tx.insertOrderIfAbsent(order)) return@inTransaction Claim(locked, null) // 같은 키로 동시에 들어온 요청이 먼저 넣었다
            lifecycle.chargeStarted(tx, order, PaymentActor.USER, accountId)
            lifecycle.scheduleReconcile(order)
            val claimed = locked.copy(chargeOrderId = order.id, pendingPlanCode = quote.plan.code, pendingAmount = quote.plan.amount.amount, pendingPeriod = if (newPeriod) quote.plan.period else null, updatedAt = writer.now())
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

    /** 예약해 둔 갈아타기를 거둔다 - 예약이 없으면 그대로(두 번 눌러도 같다). */
    fun cancelScheduled(accountId: String, subscriptionId: String): PaymentSubscription =
        store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            if (locked.scheduledPlanCode == null) locked else lifecycle.save(tx, locked, locked.status) { it.copy(scheduledPlanCode = null, scheduledAmount = null, scheduledPeriod = null) }
        }

    private fun quote(subscription: PaymentSubscription, planCode: String): PlanChangeQuote {
        val option = properties.subscription.planChange.takeIf { it.enabled }?.charge ?: throw PaymentPlanChangeNotAllowedException()
        if (subscription.status != SubscriptionStatus.ACTIVE) throw PaymentPlanChangeNotAllowedException()
        val target = plans.find(planCode)?.takeIf { it.amount.amount > 0 && it.purchasable } ?: throw PaymentItemNotPurchasableException()
        val current = plans.find(subscription.planCode) ?: throw PaymentPlanChangeNotAllowedException()
        val kind = PlanChangeRules.classify(current, target, subscription)
        if (kind == PlanChangeKind.NOT_ALLOWED) throw PaymentPlanChangeNotAllowedException()
        // 과금 중인(안 끝난) 주문이 있으면 시작하지 않는다 — 갱신이 나가 있거나 앞선 변경이 확인 중이다
        if (!lifecycle.isSettled(subscription.chargeOrderId?.let(store::findOrder))) throw PaymentPlanChangeNotAllowedException()
        val start = subscription.currentPeriodStart ?: throw PaymentPlanChangeNotAllowedException()
        val end = subscription.currentPeriodEnd ?: throw PaymentPlanChangeNotAllowedException()
        val now = writer.now()
        val currency = target.amount.currency
        val minCharge = properties.subscription.planChange.minCharge
        return when (kind) {
            PlanChangeKind.SCHEDULED -> PlanChangeQuote(target, PaymentAmount(0, currency), restartsPeriod = false, kind = kind, effectiveAt = end)
            PlanChangeKind.IMMEDIATE_NEW_PERIOD -> {
                // 새 가격 - 이번 기간에 **실제로 낸 돈**(쿠폰 · 환불 반영)의 남은 몫. 설정의 과금 방식과 상관없이 이 방식이다(차액만은 주기가 달라 성립하지 않고 전액은 손님 손해)
                val credit = currentPayments.of(subscription).sumOf { Proration.remaining(it.net, it.coversFrom(start), end, now, properties.subscription.prorationZone) }
                val due = (target.amount.amount - credit).coerceAtLeast(0)
                PlanChangeQuote(target, PaymentAmount(if (due < minCharge) 0 else due, currency), restartsPeriod = true, kind = kind, effectiveAt = now)
            }
            else -> {
                val charge = PlanChangePricing.charge(option, subscription.amount, target.amount, start, end, now, minCharge, properties.subscription.prorationZone)
                PlanChangeQuote(target, PaymentAmount(charge, currency), restartsPeriod = option == Charge.FULL_RESTART, kind = kind, effectiveAt = now)
            }
        }
    }

    private fun billingGateway(subscription: PaymentSubscription): PaymentBillingGateway =
        try {
            router.gateway(subscription.provider, PaymentBillingGateway::class).also { if (it.mode != subscription.providerMode) throw PaymentModeMismatchException() }
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentUnavailableException()
        }

    private fun owned(subscription: PaymentSubscription?, accountId: String): PaymentSubscription? = subscription?.takeIf { it.accountId == accountId }
}
