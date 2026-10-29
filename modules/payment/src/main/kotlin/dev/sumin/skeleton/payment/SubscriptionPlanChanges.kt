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
    /** 더 긴 주기로 갈 때 새 가격에서 빼 준 이번 기간 남은 몫 - 아니면 null */
    val credit: PaymentAmount? = null,
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

    fun preview(accountId: String, subscriptionId: String, planCode: String): PlanChangeQuote {
        val subscription = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        return if (subscription.isProviderManaged) hosted(subscription, planCode).quote else quote(subscription, planCode)
    }

    /**
     * @param expectedCharge 호출자가 미리보기로 본 금액 — 서버가 다시 계산한 값이 더 크면 [PaymentPlanChangeChargeChangedException].
     * @return 변경된(또는 결제 확인 중이라 [PaymentSubscription.pendingPlanCode] 가 남은) 구독. 결제사가 거절하면 [PaymentDeclinedException] 이고 플랜은 그대로다.
     */
    fun change(accountId: String, subscriptionId: String, planCode: String, expectedCharge: Long, key: IdempotencyKey): PaymentSubscription {
        store.requireNoOpenTransaction("PaymentSubscriptions.change")
        val before = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        if (before.isProviderManaged) return changeHosted(before, planCode, expectedCharge)
        val dedupeKey = "subchg:$subscriptionId:${PaymentOrderIds.dedupeKey(accountId, key).take(32)}"
        if (store.findOrderByDedupeKey(dedupeKey) != null) return before // 같은 요청의 재전송 — 이미 했다
        val gateway = billingGateway(before) // 결제 없이 예약만 하는 변경도 이 구독의 결제사를 지금 쓸 수 있는지(없음 · 모드 불일치)는 먼저 본다

        val claim = store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            val quote = quote(locked, planCode)
            if (quote.charge.amount > expectedCharge) throw PaymentPlanChangeChargeChangedException()
            if (quote.kind == PlanChangeKind.SCHEDULED) {
                // 다음 갱신부터 — 결제 없이 적어 둔다(예약을 다시 하면 덮어쓴다). 금액은 지금 값이 박혀 그 사이 가격표가 바뀌어도 이 값으로 과금한다
                return@inTransaction Claim(lifecycle.save(tx, locked, locked.status) { it.copy(scheduledPlanCode = quote.plan.code, scheduledAmount = quote.plan.amount.amount, scheduledPeriod = quote.plan.period) }, null)
            }
            val newPeriod = quote.kind == PlanChangeKind.IMMEDIATE_NEW_PERIOD
            if (quote.charge.amount == 0L && !newPeriod) {
                // 차액이 최소 금액 미만이다 — 과금 없이 바꾼다(기간은 그대로). 주기를 늘리는 변경은 공제가 덮어도 아래의 0원 주문으로 간다
                return@inTransaction Claim(lifecycle.save(tx, locked, locked.status) { lifecycle.switched(it, quote.plan.code, quote.plan.amount.amount, it.period) }, null)
            }
            // 주기를 늘리는 변경은 공제액을 주문에 박는다(원래 가격 = 결제액 + 공제액) - 중도 해지가 새 가격 전체를 이번 기간의 돈으로 센다. 공제로 결제액이 0이면 결제사 없이 끝나는 주문이다
            val credit = quote.credit?.amount?.takeIf { newPeriod && it > 0 }
            val order = lifecycle.chargeOrder(
                locked, PaymentOrderKind.SUBSCRIPTION_CHANGE, quote.plan.name, ids, quote.charge, quote.plan.code, dedupeKey,
                originalAmount = credit?.let { quote.charge.amount + it }, offProvider = quote.charge.amount == 0L,
            )
            if (!tx.insertOrderIfAbsent(order)) return@inTransaction Claim(locked, null) // 같은 키로 동시에 들어온 요청이 먼저 넣었다
            lifecycle.chargeStarted(tx, order, PaymentActor.USER, accountId)
            if (quote.charge.amount > 0) lifecycle.scheduleReconcile(order)
            val claimed = locked.copy(chargeOrderId = order.id, pendingPlanCode = quote.plan.code, pendingAmount = quote.plan.amount.amount, pendingPeriod = if (newPeriod) quote.plan.period else null, updatedAt = writer.now())
            tx.updateSubscription(claimed)
            // 0원 주문은 이 트랜잭션 뒤 곧바로 끝내는데, 그 사이 죽으면 갱신 작업이 지금 깨어 이어서 끝낸다([SubscriptionRenewTaskHandler])
            if (order.provider == PaymentOrders.FREE_PROVIDER) lifecycle.scheduleRenewal(claimed, runAt = writer.now())
            Claim(claimed, order)
        }
        val order = claim.order ?: return claim.subscription
        if (order.provider == PaymentOrders.FREE_PROVIDER) {
            // 공제가 새 가격을 덮는다 - 결제사 없이 주문을 끝내면 지급 고리가 플랜 · 주기를 바꾼다
            charges.settleFree(order)
            return store.findSubscription(subscriptionId) ?: claim.subscription
        }

        val token = encryptor.decrypt(checkNotNull(claim.subscription.billingToken) { "a live subscription has a billing key" })
        val outcome = charges.charge(gateway, claim.subscription, order, token, PaymentActor.USER)
        if (outcome is ChargeOutcome.Declined) throw PaymentDeclinedException(outcome.providerCode, gateway.billingDeclineCategory(outcome.providerCode))
        return store.findSubscription(subscriptionId) ?: claim.subscription
    }

    /**
     * 결제사가 갱신하는 구독 - 결제사가 금액을 계산하고(미리보기), 본 금액보다 크지 않을 때만 바꾸고(적용), **바꾼 뒤에** 줄을 맞춘다. 올리기만 한다.
     * 이미 그 플랜이면(재전송 · 응답 유실 뒤 웹훅이 먼저 맞췄다) 그 줄을 그대로 돌려준다. 결제사 호출은 락 · 트랜잭션 밖이다.
     */
    private fun changeHosted(before: PaymentSubscription, planCode: String, expectedCharge: Long): PaymentSubscription {
        if (before.planCode == planCode) return before
        val hosted = hosted(before, planCode)
        if (hosted.quote.charge.amount > expectedCharge) throw PaymentPlanChangeChargeChangedException()
        hosted.gateway.applyPlanChange(hosted.change)
        val applied = hosted.answer
        val target = hosted.quote.plan
        return store.inTransaction { tx ->
            val locked = tx.lockSubscription(before.id) ?: throw PaymentSubscriptionNotFoundException()
            if (locked.planCode == target.code || locked.status == SubscriptionStatus.ENDED) return@inTransaction locked // 웹훅이 먼저 맞췄거나 그 사이 끝났다
            lifecycle.save(tx, locked, locked.status) { line ->
                val switched = lifecycle.switched(line, target.code, applied.recurring.amount, target.period)
                val start = applied.periodStart
                val end = applied.periodEnd
                if (start == null || end == null) {
                    switched
                } else {
                    // 기간 번호는 웹훅 반영과 같은 규칙 - 새 기간이 시작했을 때만 올린다(같은 기간의 웹훅이 다시 세지 않는다)
                    val moved = line.currentPeriodStart?.let { start.isAfter(it) } ?: true
                    switched.copy(currentPeriodStart = start, currentPeriodEnd = end, periodSeq = if (moved) line.periodSeq + 1 else line.periodSeq)
                }
            }
        }
    }

    private class Hosted(val quote: PlanChangeQuote, val answer: HostedPlanChangeQuote, val gateway: PaymentHostedPlanChangeGateway, val change: HostedPlanChange)

    /** 결제사에 변경 견적을 묻는다 - 금액 · 통화는 결제사가 말한 그대로이고, 견적의 플랜 금액([PlanChangeQuote.plan])은 앞으로 갱신마다 내는 금액이다. */
    private fun hosted(subscription: PaymentSubscription, planCode: String): Hosted {
        val checked = checked(subscription, planCode)
        val gateway = hostedGateway(subscription)
        val change = HostedPlanChange(checkNotNull(subscription.providerSubscriptionId) { "a provider managed line has a provider subscription id" }, checked.target.code, checked.kind)
        val answer = gateway.previewPlanChange(change)
        val quote = PlanChangeQuote(checked.target.copy(amount = answer.recurring), answer.charge, answer.restartsPeriod, checked.kind, effectiveAt = writer.now(), credit = answer.credit)
        return Hosted(quote, answer, gateway, change)
    }

    private fun hostedGateway(subscription: PaymentSubscription): PaymentHostedPlanChangeGateway =
        try {
            router.gateway(subscription.provider, PaymentHostedPlanChangeGateway::class).also { if (it.mode != subscription.providerMode) throw PaymentModeMismatchException() }
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentUnavailableException()
        } catch (e: PaymentCapabilityUnsupportedException) {
            throw PaymentPlanChangeNotAllowedException()
        }

    private class Claim(val subscription: PaymentSubscription, val order: PaymentOrder?)

    /** 예약해 둔 갈아타기를 거둔다 - 예약이 없으면 그대로(두 번 눌러도 같다). */
    fun cancelScheduled(accountId: String, subscriptionId: String): PaymentSubscription =
        store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            if (locked.scheduledPlanCode == null) locked else lifecycle.save(tx, locked, locked.status) { it.copy(scheduledPlanCode = null, scheduledAmount = null, scheduledPeriod = null) }
        }

    private data class Checked(val option: Charge, val target: SubscriptionPlan, val kind: PlanChangeKind)

    /** 설정 · 상태 · 대상 플랜 · 판정 · 과금 중 주문을 본다 - 토스 경로([quote])와 결제사 경로([hosted])가 같다. 결제사 관리 구독은 플랜 표의 통화로 비교하고 내리기는 거절한다. */
    private fun checked(subscription: PaymentSubscription, planCode: String): Checked {
        val option = properties.subscription.planChange.takeIf { it.enabled }?.charge ?: throw PaymentPlanChangeNotAllowedException()
        if (subscription.status != SubscriptionStatus.ACTIVE) throw PaymentPlanChangeNotAllowedException()
        val target = plans.find(planCode)?.takeIf { it.amount.amount > 0 && it.purchasable } ?: throw PaymentItemNotPurchasableException()
        val current = plans.find(subscription.planCode) ?: throw PaymentPlanChangeNotAllowedException()
        val kind = PlanChangeRules.classify(current, target, subscription, if (subscription.isProviderManaged) current.amount.currency else subscription.amount.currency)
        if (kind == PlanChangeKind.NOT_ALLOWED) throw PaymentPlanChangeNotAllowedException()
        if (kind == PlanChangeKind.SCHEDULED && subscription.isProviderManaged) throw PaymentPlanChangeDowngradeUnsupportedException()
        // 과금 중인(안 끝난) 주문이 있으면 시작하지 않는다 — 갱신이 나가 있거나 앞선 변경이 확인 중이다
        if (!lifecycle.isSettled(subscription.chargeOrderId?.let(store::findOrder))) throw PaymentPlanChangeNotAllowedException()
        return Checked(option, target, kind)
    }

    private fun quote(subscription: PaymentSubscription, planCode: String): PlanChangeQuote {
        val (option, target, kind) = checked(subscription, planCode)
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
                PlanChangeQuote(target, PaymentAmount(if (due < minCharge) 0 else due, currency), restartsPeriod = true, kind = kind, effectiveAt = now, credit = PaymentAmount(minOf(credit, target.amount.amount), currency).takeIf { it.amount > 0 })
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
