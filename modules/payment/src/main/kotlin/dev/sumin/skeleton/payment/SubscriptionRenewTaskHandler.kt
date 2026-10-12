package dev.sumin.skeleton.payment

import dev.sumin.skeleton.crypto.TextEncryptor
import org.slf4j.LoggerFactory

/**
 * `payment-subscription-renew` — 구독 하나를 기간 끝에 깨우는 **운전자 작업**(스펙 `2026-10-09-subscription-design.md` §5). 토스는 스케줄링을 하지 않으므로 갱신은 우리 장부의 작업이다.
 *
 * 한 걸음: **구독 줄을 잠그고 다시 읽어**(해지 · 탈퇴 뒤 깨어난 작업은 결제사를 부르지 않는다) 기한이 됐을 때만 진행한다.
 *  - `ACTIVE` · `PAST_DUE` 이고 기한이 지났다 → 앱이 막지 않았으면([SubscriptionRenewalGuard]) 과금 주문을 **넣는다**(`dedupe_key = sub:<id>:<다음 기간>:<시도>` 유니크 — 여러 워커가 동시에 와도 한 줄)
 *    — 선점(`CONFIRMING` + 조회 작업, 한 트랜잭션) → 승인(락 밖) → 확정. 주문 줄은 **잠그지 않고 읽기만** 한다(락 순서 주문 → 구독).
 *  - 지금 시도의 주문이 이미 있으면 **새 주문을 만들지 않는다**. 그 주문이 실패로 끝나면(거절 · 조회가 끝내 못 물어 죽음) 같은 트랜잭션에서 구독이 반응한다 — `PAST_DUE` + 다음 시도 또는 종료.
 *  - `CANCELING` 이고 기간이 끝났다 → 끝낸다.
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

        /** [token] 은 복호화한 빌링키 — `toString` 에 싣지 않는다. */
        class Charge(val subscription: PaymentSubscription, val order: PaymentOrder, val token: String) : Step {
            override fun toString(): String = "Charge(subscription=${subscription.id}, order=${order.id})"
        }
    }

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The subscription renewal task")
        val id = run.subjectId
        val before = store.findSubscription(id) ?: return
        val chargeable = (before.status == SubscriptionStatus.ACTIVE || before.status == SubscriptionStatus.PAST_DUE) && due(before)
        // 결제사 · 모드는 주문을 선점하기 **전에** 확인한다 — 보낼 수 없는 과금의 주문이 남지 않게(테스트 키 → 실결제 키 전환 같은 길)
        val gateway = if (chargeable) billingGateway(before) else null
        // 앱의 판단은 락 밖에서 받는다(앱이 DB 를 읽는다) — 기한이 아니거나 끝난 구독에는 묻지 않는다
        val allowed = if (chargeable) guard.allows(before.accountId) else true
        val step = step(id, allowed)
        if (step is Step.Charge) {
            val outcome = charges.charge(checkNotNull(gateway), step.subscription, step.order, step.token, PaymentActor.SYSTEM)
            // 모름이면 줄은 CONFIRMING 그대로다 — 다시 보내지 않는다. 조회 작업이 끝내고, 못 끝내면 죽은 조회 작업이 구독에 알린다([SubscriptionLifecycle.onOrderClosed])
            log.info("subscription renewal charge subscription={} order={} outcome={}", id, step.order.id, outcome::class.simpleName)
        }
    }

    private fun billingGateway(subscription: PaymentSubscription): PaymentBillingGateway {
        val gateway = try {
            router.gateway(subscription.provider, PaymentBillingGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentTaskRetryException("No payment provider is available to charge subscription ${subscription.id}")
        } catch (e: PaymentCapabilityUnsupportedException) {
            throw PaymentTaskPermanentException("The payment provider of subscription ${subscription.id} cannot charge billing keys")
        }
        if (gateway.mode != subscription.providerMode) throw PaymentTaskPermanentException("Subscription ${subscription.id} was created under another payment mode")
        return gateway
    }

    private fun due(subscription: PaymentSubscription): Boolean = subscription.currentPeriodEnd?.let { !writer.now().isBefore(it) } ?: false

    private fun step(id: String, allowed: Boolean): Step =
        store.inTransaction { tx ->
            val subscription = tx.lockSubscription(id) ?: return@inTransaction Step.Done
            when (subscription.status) {
                SubscriptionStatus.ENDED, SubscriptionStatus.PENDING -> Step.Done // 첫 결제는 주문이 끝 상태가 될 때 구독이 반응한다
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
                    renew(tx, subscription)
                }
            }
        }

    private fun renew(tx: PaymentLedgerTx, subscription: PaymentSubscription): Step {
        val chargeOrder = subscription.chargeOrderId?.let(store::findOrder)
        // 지금 시도의 주문이 이미 있다 — 진행 중이거나 지급(기간 넘기기)이 아직이다. 새 주문을 만들지 않는다. 끝 상태가 되면 그 트랜잭션에서 구독이 반응한다
        if (chargeOrder != null && chargeOrder.dedupeKey == lifecycle.currentKey(subscription)) return Step.Done
        // 플랜 변경 결제가 아직 오가는 중이다 — 과금하지 않는다. 변경이 끝나면(지급 · 실패) 그 쪽에서 이 작업을 다시 깨운다([SubscriptionLifecycle.onOrderPaid] · onOrderClosed)
        if (chargeOrder?.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE && !lifecycle.isSettled(chargeOrder)) return Step.Done
        // 다음 시도는 앞선 시도가 닫힌 때부터 재시도 간격이 지나야 한다 — 같은 구독을 깨우는 작업이 둘 있어도 간격 없이 곧바로 또 과금하지 않는다
        val failedAt = chargeOrder?.closedAt
        if (subscription.status == SubscriptionStatus.PAST_DUE && failedAt != null && writer.now().isBefore(failedAt.plus(retryInterval))) return Step.Done
        val planName = plans.find(subscription.planCode)?.name ?: subscription.planCode
        val order = lifecycle.chargeOrder(subscription, PaymentOrderKind.SUBSCRIPTION_RENEWAL, planName)
        if (!tx.insertOrderIfAbsent(order)) return Step.Done // 같은 시도의 주문이 이미 있다 — 그쪽이 과금한다
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
