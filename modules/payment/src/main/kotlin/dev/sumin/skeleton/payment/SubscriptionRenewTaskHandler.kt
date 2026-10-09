package dev.sumin.skeleton.payment

import dev.sumin.skeleton.crypto.TextEncryptor
import org.slf4j.LoggerFactory

/**
 * `payment-subscription-renew` — 구독 하나를 기간 끝에 깨우는 **운전자 작업**(스펙 `2026-10-09-subscription-design.md` §5). 토스는 스케줄링을 하지 않으므로 갱신은 우리 장부의 작업이다.
 *
 * 한 걸음: **구독 줄을 잠그고 다시 읽어**(해지 · 탈퇴 뒤 깨어난 작업은 결제사를 부르지 않는다) 기한이 됐을 때만 진행한다.
 *  - `ACTIVE` · `PAST_DUE` 이고 기한이 지났다 → 앱이 막지 않았으면([SubscriptionRenewalGuard]) 과금 주문을 **넣는다**(`dedupe_key = sub:<id>:<다음 기간>:<시도>` 유니크 — 여러 워커가 동시에 와도 한 줄)
 *    — 선점(`CONFIRMING` + 조회 작업, 한 트랜잭션) → 승인(락 밖) → 확정. 주문 줄은 **잠그지 않고 읽기만** 한다(락 순서 주문 → 구독).
 *  - 지금 과금 주문이 끝 상태가 아니면 **새 주문을 만들지 않고** 나중에 다시 본다(던져서 큐가 다시 부른다). 실패로 닫혔으면 `PAST_DUE` + 다음 시도 또는 종료.
 *  - `CANCELING` 이고 기간이 끝났다 → 끝낸다. `PENDING`(첫 결제 확인 중)은 안전망이다: 그 주문이 실패로 닫혔으면 구독을 끝내 자리를 푼다.
 * **모름이면 다시 보내지 않는다** — 기존 조회 작업이 주문 번호로 결제사에 물어 끝낸다. 기간은 여기서 넘기지 않는다(지급 고리 [SubscriptionLifecycle.onOrderPaid] 에서만).
 *
 * **바깥 트랜잭션 안에서 부르면 던진다** — 결제사를 부르는 코드다.
 */
class SubscriptionRenewTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val plans: SubscriptionPlans,
    private val encryptor: TextEncryptor,
    private val lifecycle: SubscriptionLifecycle,
    private val charges: SubscriptionCharges,
    private val guard: SubscriptionRenewalGuard,
    private val writer: PaymentLedgerWriter,
    private val retryInterval: java.time.Duration,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.SUBSCRIPTION_RENEW

    private sealed interface Step {
        data object Done : Step

        data class Wait(val reason: String) : Step

        data class Charge(val subscription: PaymentSubscription, val order: PaymentOrder, val token: String) : Step
    }

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The subscription renewal task")
        val id = run.subjectId
        val before = store.findSubscription(id) ?: return
        // 앱의 판단은 락 밖에서 받는다(앱이 DB 를 읽는다) — 기한이 아니거나 끝난 구독에는 묻지 않는다
        val allowed = if ((before.status == SubscriptionStatus.ACTIVE || before.status == SubscriptionStatus.PAST_DUE) && due(before)) guard.allows(before.accountId) else true
        when (val step = step(id, allowed)) {
            Step.Done -> Unit
            is Step.Wait -> throw PaymentTaskRetryException("Subscription $id: ${step.reason}")
            is Step.Charge -> {
                val gateway = try {
                    router.gateway(step.subscription.provider, PaymentBillingGateway::class)
                } catch (e: PaymentProviderNotFoundException) {
                    throw PaymentTaskRetryException("No payment provider is available to charge subscription $id")
                } catch (e: PaymentCapabilityUnsupportedException) {
                    throw PaymentTaskPermanentException("The payment provider of subscription $id cannot charge billing keys")
                }
                if (gateway.mode != step.subscription.providerMode) throw PaymentTaskPermanentException("Subscription $id was created under another payment mode")
                val outcome = charges.charge(gateway, step.subscription, step.order, step.token, PaymentActor.SYSTEM) { tx, _ ->
                    tx.lockSubscription(id)?.let { locked -> if (locked.status != SubscriptionStatus.ENDED) lifecycle.onChargeFailed(tx, locked) }
                }
                log.info("subscription renewal charge subscription={} order={} outcome={}", id, step.order.id, outcome::class.simpleName)
                // 모름: 줄은 CONFIRMING 그대로이고 조회 작업이 끝낸다 — 이 작업도 다시 와서 결과를 본다. 다시 보내지 않는다
                if (outcome is ChargeOutcome.Unknown) throw PaymentTaskRetryException("The charge of subscription $id is not settled yet")
            }
        }
    }

    private fun due(subscription: PaymentSubscription): Boolean = subscription.currentPeriodEnd?.let { !writer.now().isBefore(it) } ?: false

    private fun step(id: String, allowed: Boolean): Step =
        store.inTransaction { tx ->
            val subscription = tx.lockSubscription(id) ?: return@inTransaction Step.Done
            val chargeOrder = subscription.chargeOrderId?.let(store::findOrder)
            when (subscription.status) {
                SubscriptionStatus.ENDED -> Step.Done
                SubscriptionStatus.PENDING -> pending(tx, subscription, chargeOrder)
                SubscriptionStatus.CANCELING -> {
                    if (due(subscription)) lifecycle.end(tx, subscription, SubscriptionEndReason.USER_CANCELED)
                    Step.Done
                }
                SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE -> {
                    if (!due(subscription)) return@inTransaction Step.Done
                    if (!allowed) {
                        lifecycle.end(tx, subscription, SubscriptionEndReason.RENEWAL_BLOCKED)
                        return@inTransaction Step.Done
                    }
                    renew(tx, subscription, chargeOrder)
                }
            }
        }

    /** 첫 결제 확인 중인 구독의 안전망 — 과금 주문이 닫혔으면 끝내 자리를 푼다. 진행 중이면 기다린다. */
    private fun pending(tx: PaymentLedgerTx, subscription: PaymentSubscription, chargeOrder: PaymentOrder?): Step {
        chargeOrder ?: return Step.Done // 카드를 내지 않은 줄 — 할 일이 없다
        return when {
            chargeOrder.status.isClosed -> {
                lifecycle.end(tx, subscription, SubscriptionEndReason.FIRST_PAYMENT_DECLINED)
                Step.Done
            }
            chargeOrder.status == PaymentOrderStatus.REFUNDED -> {
                lifecycle.end(tx, subscription, SubscriptionEndReason.REFUNDED)
                Step.Done
            }
            else -> Step.Wait("the first charge is not settled yet")
        }
    }

    private fun renew(tx: PaymentLedgerTx, subscription: PaymentSubscription, chargeOrder: PaymentOrder?): Step {
        val sequence = subscription.periodSeq + 1
        val currentKey = "sub:${subscription.id}:$sequence:${subscription.attempt}"
        if (chargeOrder != null && chargeOrder.dedupeKey == currentKey) {
            return when {
                chargeOrder.status.isClosed -> {
                    lifecycle.onChargeFailed(tx, subscription)
                    Step.Done
                }
                chargeOrder.status == PaymentOrderStatus.REFUNDED -> {
                    lifecycle.end(tx, subscription, SubscriptionEndReason.REFUNDED)
                    Step.Done
                }
                // 진행 중이거나 돈은 들어왔는데 지급(기간 넘기기)이 아직이다 — 새 주문을 만들지 않는다
                else -> Step.Wait("the charge of period $sequence is not settled yet")
            }
        }
        // 다음 시도는 앞선 시도가 닫힌 때부터 재시도 간격이 지나야 한다 — 같은 구독을 깨우는 작업이 둘 있어도(안전망 · 재시도) 간격 없이 곧바로 또 과금하지 않는다
        val failedAt = chargeOrder?.closedAt
        if (subscription.status == SubscriptionStatus.PAST_DUE && failedAt != null && writer.now().isBefore(failedAt.plus(retryInterval))) return Step.Done
        val planName = plans.find(subscription.planCode)?.name ?: subscription.planCode
        val order = lifecycle.chargeOrder(subscription, PaymentOrderKind.SUBSCRIPTION_RENEWAL, planName)
        if (!tx.insertOrderIfAbsent(order)) return Step.Wait("another worker is charging period $sequence") // 같은 시도의 주문이 이미 있다 — 그쪽이 과금한다
        lifecycle.chargeStarted(tx, order, PaymentActor.SYSTEM)
        lifecycle.scheduleReconcile(order)
        val claimed = subscription.copy(chargeOrderId = order.id, updatedAt = writer.now())
        tx.updateSubscription(claimed)
        val token = encryptor.decrypt(checkNotNull(subscription.billingToken) { "a live subscription has a billing key" })
        return Step.Charge(claimed, order, token)
    }

    /**
     * 갱신 작업이 죽었다 — 표시할 줄이 없다(구독 줄에는 「확인 필요」 칸이 없다). 지금 과금 주문이 `CONFIRMING` 으로 남았으면 조회 작업의 죽음이 그 주문에 표시한다(`CONFIRM_UNRESOLVED`).
     * 작업이 죽은 사실은 `alert` 의 `JOB_DEAD` 와 작업 줄이 말한다. 기간이 지났는데 과금이 안 된 채 `ACTIVE` 로 남는 구독은 무료 이용이 되는 쪽이라 돈 사고는 아니다(스펙 §13).
     */
    override fun onDead(run: PaymentTaskRun) = Unit
}
