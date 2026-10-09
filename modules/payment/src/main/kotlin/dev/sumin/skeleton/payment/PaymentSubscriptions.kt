package dev.sumin.skeleton.payment

import dev.sumin.skeleton.crypto.TextEncryptor
import java.security.SecureRandom
import java.util.Random
import org.slf4j.LoggerFactory

/** [billing] 은 카드 등록 창을 여는 값. 같은 요청의 재전송이 이미 시작한 구독이면 null. */
data class StartedSubscription(val subscription: PaymentSubscription, val billing: BillingSetup?, val replayed: Boolean)

/**
 * 구독 서비스(스펙 `2026-10-09-subscription-design.md` §5 · §7) — 만들기 · 카드 등록 + 첫 결제 · 내 구독 · 해지 예약. 모든 전이는 구독 줄을 잠그고 **다시 읽은 뒤** 판단하며,
 * 결제사 호출은 락 · 트랜잭션 밖이다(빌링키 발급 → 자리 선점 + 첫 주문(커밋) → 승인 → 확정). 남의 구독과 없는 구독은 같은 [PaymentSubscriptionNotFoundException] 이다.
 * 빌링키는 [encryptor] 로 암호화해 저장하고, 평문은 결제사를 부르는 동안에만 메모리에 있다.
 */
class PaymentSubscriptions(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val plans: SubscriptionPlans,
    private val encryptor: TextEncryptor,
    private val lifecycle: SubscriptionLifecycle,
    private val charges: SubscriptionCharges,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    private val random: Random = SecureRandom(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun plans(): List<SubscriptionPlan> = plans.list()

    /** 이 계정의 가장 최근 구독(끝난 것 포함, 시작하지 않은 시도는 제외). 앱은 이것을 **읽을 때** `status.isInUse` 로 판단한다. */
    fun current(accountId: String): PaymentSubscription? = store.findLatestSubscription(accountId)

    fun recentOrders(subscriptionId: String, limit: Int = RECENT_ORDERS): List<PaymentOrder> = store.listSubscriptionOrders(subscriptionId, limit)

    /** `PENDING` 줄을 만들고 카드 등록 창 값을 돌려준다. 같은 키로 다시 부르면 같은 줄이다. */
    fun start(accountId: String, planCode: String, key: IdempotencyKey): StartedSubscription {
        val id = PaymentOrderIds.subscriptionId(accountId, key)
        store.findSubscription(id)?.let { return replay(it, planCode) }

        val plan = plans.find(planCode)?.takeIf { it.amount.amount > 0 } ?: throw PaymentItemNotPurchasableException()
        SubscriptionPeriod.parse(plan.period)
        if (store.findLiveSubscription(accountId) != null) throw PaymentAlreadySubscribedException()
        val gateway = billingGateway(plan.amount) ?: throw PaymentUnavailableException()
        val customerKey = PaymentOrderIds.newCustomerKey(random)
        val setup = gateway.billingSetup(customerKey) ?: throw PaymentUnavailableException()

        val now = writer.now()
        val subscription = PaymentSubscription(
            id = id,
            accountId = accountId,
            provider = gateway.providerId.trim().lowercase(),
            providerMode = gateway.mode,
            status = SubscriptionStatus.PENDING,
            planCode = plan.code,
            amount = plan.amount,
            period = plan.period,
            anchorAt = null,
            periodSeq = 0,
            currentPeriodStart = null,
            currentPeriodEnd = null,
            chargeOrderId = null,
            attempt = 1,
            customerKey = customerKey,
            billingToken = null,
            cardIssuer = null,
            cardLast4 = null,
            activeKey = null,
            pendingPlanCode = null,
            pendingAmount = null,
            endReason = null,
            endedAt = null,
            accountErasedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        if (!store.inTransaction { it.insertSubscriptionIfAbsent(subscription) }) {
            // 동시에 같은 키로 들어온 다른 요청이 먼저 넣었다 — 그 줄이 곧 답이다
            return replay(checkNotNull(store.findSubscription(id)) { "the subscription for this idempotency key disappeared" }, planCode)
        }
        return StartedSubscription(subscription, setup, replayed = false)
    }

    /**
     * 카드 등록 창이 돌려준 값으로 빌링키를 받고 첫 결제를 한다. **발급(락 밖) → 자리 선점 + 첫 주문 + 조회 작업(한 트랜잭션) → 승인(락 밖) → 확정**.
     * 돌려주는 줄이 `ACTIVE` 면 끝났고 `PENDING` 이면 확인 중이다(HTTP 202). 확정 거절이면 구독을 끝내고 [PaymentDeclinedException].
     * 선점에 지면(동시 요청 · 이미 구독 중) 방금 받은 빌링키를 지운다.
     *
     * **바깥 트랜잭션 안에서 부르면 던진다**([IllegalStateException]) — 결제사를 부르는 코드다.
     */
    fun activate(accountId: String, subscriptionId: String, providerPayload: Map<String, Any?>): PaymentSubscription {
        store.requireNoOpenTransaction("PaymentSubscriptions.activate")
        val subscription = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        when {
            subscription.status == SubscriptionStatus.ENDED -> throw PaymentSubscriptionNotActivatableException()
            subscription.status != SubscriptionStatus.PENDING || subscription.chargeOrderId != null -> return subscription // 같은 요청의 재전송 — 이미 시작했거나 확인 중이다
        }
        val gateway = router.gateway(subscription.provider, PaymentBillingGateway::class)
        if (gateway.mode != subscription.providerMode) throw PaymentModeMismatchException()
        val customerKey = checkNotNull(subscription.customerKey)

        val issued = try {
            gateway.issue(customerKey, providerPayload)
        } catch (e: Exception) {
            if (PaymentFailures.outcomeOf(e) == PaymentFailureOutcome.DEFINITIVE) {
                throw PaymentDeclinedException(PaymentFailures.providerCodeOf(e), gateway.billingDeclineCategory(PaymentFailures.providerCodeOf(e)))
            }
            throw e
        }

        val claim = try {
            claim(accountId, subscription, gateway, issued)
        } catch (e: PaymentLedgerConflictException) {
            revokeQuietly(gateway, customerKey, issued.token)
            throw PaymentAlreadySubscribedException()
        } catch (e: Exception) {
            revokeQuietly(gateway, customerKey, issued.token)
            throw e
        }
        if (claim.order == null) {
            // 같은 구독을 동시에 시작한 다른 요청이 이겼다 — 그쪽의 빌링키가 쓰인다
            revokeQuietly(gateway, customerKey, issued.token)
            return claim.subscription
        }

        val outcome = charges.charge(gateway, claim.subscription, claim.order, issued.token, PaymentActor.USER) { tx, _ ->
            val locked = tx.lockSubscription(subscriptionId)
            if (locked != null && locked.status == SubscriptionStatus.PENDING) lifecycle.end(tx, locked, SubscriptionEndReason.FIRST_PAYMENT_DECLINED)
        }
        val after = store.findSubscription(subscriptionId) ?: claim.subscription
        if (outcome is ChargeOutcome.Declined) {
            throw PaymentDeclinedException(outcome.providerCode, gateway.billingDeclineCategory(outcome.providerCode))
        }
        return after
    }

    /** 해지 예약 — `ACTIVE` → `CANCELING`(기간 끝까지 이용), `PAST_DUE` 는 바로 끝. 다시 눌러도 같은 답. 시작하지 않은 구독은 [PaymentSubscriptionNotCancelableException]. */
    fun cancel(accountId: String, subscriptionId: String): PaymentSubscription =
        store.inTransaction { tx ->
            val subscription = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            when (subscription.status) {
                SubscriptionStatus.ACTIVE -> lifecycle.save(tx, subscription, SubscriptionStatus.CANCELING)
                SubscriptionStatus.PAST_DUE -> lifecycle.end(tx, subscription, SubscriptionEndReason.USER_CANCELED)
                SubscriptionStatus.CANCELING, SubscriptionStatus.ENDED -> subscription
                SubscriptionStatus.PENDING -> throw PaymentSubscriptionNotCancelableException()
            }
        }

    private class Claim(val subscription: PaymentSubscription, val order: PaymentOrder?)

    /** ② 자리(`active_key`) 선점 + 빌링키(암호문) + 첫 주문(`CONFIRMING`) + 조회 작업 — 한 트랜잭션. 락을 잡은 줄이 이미 시작됐으면 주문 없이 그 줄을 돌려준다. */
    private fun claim(accountId: String, subscription: PaymentSubscription, gateway: PaymentBillingGateway, issued: BillingIssue): Claim =
        store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscription.id), accountId) ?: throw PaymentSubscriptionNotFoundException()
            if (locked.status == SubscriptionStatus.ENDED) throw PaymentSubscriptionNotActivatableException()
            if (locked.status != SubscriptionStatus.PENDING || locked.chargeOrderId != null) return@inTransaction Claim(locked, null)

            val planName = plans.find(locked.planCode)?.name ?: locked.planCode
            val order = lifecycle.chargeOrder(locked, PaymentOrderKind.SUBSCRIPTION_INITIAL, planName, random)
            check(tx.insertOrderIfAbsent(order)) { "the first order of a subscription already exists" }
            lifecycle.chargeStarted(tx, order, PaymentActor.USER, accountId)
            lifecycle.scheduleReconcile(order)
            // 상태는 `PENDING` 그대로다(첫 결제가 끝나야 `ACTIVE`) — 자리 · 빌링키 · 과금 주문만 박는다. 자리가 겹치면 저장소가 [PaymentLedgerConflictException] 으로 이 트랜잭션을 되돌린다
            val claimed = locked.copy(
                activeKey = accountId,
                billingToken = encryptor.encrypt(issued.token),
                cardIssuer = LedgerValues.cardIssuer(issued.card?.issuer),
                cardLast4 = LedgerValues.cardLast4(issued.card?.last4),
                chargeOrderId = order.id,
                attempt = 1,
                updatedAt = writer.now(),
            )
            tx.updateSubscription(claimed)
            // 안전망: 응답을 못 받은 첫 결제가 끝내 실패로 닫혀도 자리(`active_key`)가 영원히 잡혀 있지 않게 — 갱신 작업이 PENDING 도 본다
            lifecycle.scheduleRenewal(claimed, runAt = writer.now().plus(properties.reconcile.firstDelay))
            Claim(claimed, order)
        }

    private fun replay(existing: PaymentSubscription, planCode: String): StartedSubscription {
        if (existing.planCode != planCode) throw PaymentIdempotencyConflictException()
        val setup = if (existing.status == SubscriptionStatus.PENDING && existing.chargeOrderId == null) {
            val gateway = try {
                router.gateway(existing.provider, PaymentBillingGateway::class)
            } catch (e: PaymentProviderNotFoundException) {
                throw PaymentUnavailableException()
            }
            gateway.billingSetup(checkNotNull(existing.customerKey)) ?: throw PaymentUnavailableException()
        } else {
            null
        }
        return StartedSubscription(existing, setup, replayed = true)
    }

    /** 카드 등록 창을 열 수 있는 결제사 — 없으면 null. */
    private fun billingGateway(amount: PaymentAmount): PaymentBillingGateway? =
        try {
            router.resolve(null, amount, null, PaymentBillingGateway::class)
        } catch (e: PaymentRoutingException) {
            log.warn("no payment provider can register a card: {}", e.message)
            null
        } catch (e: PaymentProviderNotFoundException) {
            log.warn("the payment provider for a card registration is not available: {}", e.providerId)
            null
        }

    /** 선점에서 진 쪽의 방금 받은 빌링키를 지운다 — 못 지워도 응답은 바꾸지 않는다(토큰은 어디에도 남기지 않으므로 로그에 사실만). */
    private fun revokeQuietly(gateway: PaymentBillingGateway, customerKey: String, token: String) {
        try {
            gateway.revoke(customerKey, token)
        } catch (e: Exception) {
            log.warn("a billing key issued for a lost subscription claim could not be removed exception={}", e.javaClass.simpleName)
        }
    }

    private fun owned(subscription: PaymentSubscription?, accountId: String): PaymentSubscription? = subscription?.takeIf { it.accountId == accountId }

    companion object {
        const val RECENT_ORDERS = 5
    }
}

/**
 * `payment-subscription-revoke` — 끝난 구독의 빌링키를 결제사에서 지운 뒤 줄의 빌링키 · 고객 키를 비운다. 지우기가 실패하면 줄은 그대로 두고 던져 큐가 다시 한다(결제사에 살아 있는 카드를 잊지 않는다).
 * 이미 없는 키는 결제사 능력이 정상으로 돌려준다([PaymentBillingGateway.revoke]). **바깥 트랜잭션 안에서 부르면 던진다** — 결제사를 부르는 코드다.
 */
class SubscriptionRevokeTaskHandler(
    private val store: PaymentLedgerStore,
    private val router: PaymentProviderRouter,
    private val encryptor: TextEncryptor,
    private val writer: PaymentLedgerWriter,
) : PaymentTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val type: String = PaymentTaskTypes.SUBSCRIPTION_REVOKE

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The billing key removal task")
        val id = run.subjectId
        val subscription = store.findSubscription(id) ?: return
        val cipher = subscription.billingToken ?: return
        if (subscription.status != SubscriptionStatus.ENDED) return // 살아 있는 구독의 빌링키는 지우지 않는다
        val gateway = try {
            router.gateway(subscription.provider, PaymentBillingGateway::class)
        } catch (e: PaymentProviderNotFoundException) {
            throw PaymentTaskRetryException("No payment provider is available to remove the billing key of subscription $id")
        } catch (e: PaymentCapabilityUnsupportedException) {
            throw PaymentTaskPermanentException("The payment provider of subscription $id cannot remove billing keys")
        }
        try {
            gateway.revoke(subscription.customerKey.orEmpty(), encryptor.decrypt(cipher))
        } catch (e: Exception) {
            log.warn("billing key removal failed subscription={} attempt={}/{} exception={}", id, run.attempt, run.maxAttempts, e.javaClass.simpleName)
            throw PaymentTaskRetryException("Billing key removal failed for subscription $id")
        }
        store.inTransaction { tx ->
            val locked = tx.lockSubscription(id) ?: return@inTransaction
            if (locked.status == SubscriptionStatus.ENDED && locked.billingToken != null) {
                tx.updateSubscription(locked.copy(billingToken = null, customerKey = null, updatedAt = writer.now()))
            }
        }
    }
}
