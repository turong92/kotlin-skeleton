package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 서명된 웹훅이 곧 사실인 결제사([PaymentPushGateway])의 사건을 장부에 옮긴다 — 결제(단건 · 구독)와 구독 상태. **조회하지 않는다**: 서명이 진위를, 결제사가 말한 금액 · 통화가 사실을 보증한다.
 * 모든 걸음은 호출하는 처리 작업의 한 트랜잭션 안이고 몇 번을 불러도 같은 결과다(같은 거래는 한 번, 낡은 구독 사건은 무시).
 *
 * 돌려주는 값: null = 반영했다(또는 이미 반영돼 있다), 문자열 = 반영하지 않은 이유(수신함 줄이 `IGNORED` 로 닫히며 `last_error` 에 이 코드가 적힌다).
 * 락: 단건은 주문 줄 하나, 구독은 구독 줄 하나만 잠그고 구독 결제의 주문은 **넣기만** 한다(주문 → 구독 순서와 부딪히지 않는다).
 *
 * @param subscriptions 구독을 켠 조립에서만 — 없으면 구독 사건은 반영하지 않는다.
 */
class PaymentPushedEvents(
    private val store: PaymentLedgerStore,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val subscriptions: SubscriptionLifecycle? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun apply(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String? {
        val provider = gateway.providerId.trim().lowercase()
        return when (event) {
            is PushedPayment -> if (event.ourOrderId != null) oneTime(tx, provider, gateway.mode, event) else subscriptionPayment(tx, provider, gateway.mode, event)
            is PushedSubscription -> subscriptionState(tx, provider, gateway.mode, event)
        }
    }

    private fun oneTime(tx: PaymentLedgerTx, provider: String, mode: PaymentMode, event: PushedPayment): String? {
        val order = tx.lockOrder(checkNotNull(event.ourOrderId)) ?: return UNKNOWN_ORDER
        if (order.provider != provider || order.kind != PaymentOrderKind.ONE_TIME) return UNKNOWN_ORDER
        if (event.ourAccountId != null && event.ourAccountId != order.accountId) return ACCOUNT_MISMATCH
        if (order.providerMode != mode) return MODE_MISMATCH
        if (order.status.isPaidFamily) return if (order.providerPaymentId == event.providerPaymentId) null else ORDER_MISMATCH
        val owner = tx.findOrderByProviderPayment(provider, event.providerPaymentId)
        if (owner != null && owner.id != order.id) return ORDER_MISMATCH
        // 금액 · 통화는 결제사가 받은 그대로다 — 주문을 만들 때의 값(우리 카탈로그의 통화)이 아니라 구매자가 낸 통화로 바꾼다
        writer.transition(tx, order, PaymentOrderStatus.PAID, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK) {
            it.copy(
                providerPaymentId = event.providerPaymentId,
                amount = event.total,
                paidAmount = event.total.amount,
                taxAmount = event.tax,
                paidAt = event.paidAt,
                method = LedgerValues.method(event.method),
                cardIssuer = if (it.accountErasedAt == null) LedgerValues.cardIssuer(event.card?.issuer) else null,
                cardLast4 = if (it.accountErasedAt == null) LedgerValues.cardLast4(event.card?.last4) else null,
                failureCode = null,
                closedAt = null,
                reviewReason = null,
            )
        }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        return null
    }

    private fun subscriptionPayment(tx: PaymentLedgerTx, provider: String, mode: PaymentMode, event: PushedPayment): String? {
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        val subscription = lock(tx, provider, event.ourSubscriptionId, event.providerSubscriptionId) ?: return UNKNOWN_SUBSCRIPTION
        if (event.ourAccountId != null && event.ourAccountId != subscription.accountId) return ACCOUNT_MISMATCH
        if (subscription.providerMode != mode) return MODE_MISMATCH
        if (tx.findOrderByProviderPayment(provider, event.providerPaymentId) != null) return null // 같은 거래 — 이미 반영했다

        val kind = if (subscription.chargeOrderId == null) PaymentOrderKind.SUBSCRIPTION_INITIAL else PaymentOrderKind.SUBSCRIPTION_RENEWAL
        val order = lifecycle.chargeOrder(subscription, kind, subscription.planCode, amount = event.total, dedupeKey = "$provider:${event.providerPaymentId}").copy(
            status = PaymentOrderStatus.PAID,
            providerPaymentId = event.providerPaymentId,
            paidAmount = event.total.amount,
            taxAmount = event.tax,
            method = LedgerValues.method(event.method),
            cardIssuer = LedgerValues.cardIssuer(event.card?.issuer),
            cardLast4 = LedgerValues.cardLast4(event.card?.last4),
            confirmStartedAt = null,
            checkoutExpiresAt = event.paidAt,
            paidAt = event.paidAt,
        )
        if (!tx.insertOrderIfAbsent(order)) return null
        tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK, to = PaymentOrderStatus.PAID))
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))

        val base = subscription.copy(
            providerSubscriptionId = subscription.providerSubscriptionId ?: event.providerSubscriptionId,
            amount = event.total,
            chargeOrderId = order.id,
        )
        when {
            subscription.status == SubscriptionStatus.PENDING -> activate(tx, lifecycle, base, event.periodStart ?: event.paidAt, event.periodEnd)
            // 갱신이 끝내 결제돼 연체가 풀린다 — 더 새로운 구독 사건이 이미 상태를 정했다면 따르지 않는다
            subscription.status == SubscriptionStatus.PAST_DUE && !event.occurredAt.isBefore(subscription.providerSyncedAt ?: event.occurredAt) ->
                lifecycle.save(tx, base, SubscriptionStatus.ACTIVE) { it.copy(attempt = 1) }
            subscription.status == SubscriptionStatus.ENDED -> log.warn("a Paddle payment arrived for an ended subscription subscription={} order={}", subscription.id, order.id)
            else -> tx.updateSubscription(base.copy(updatedAt = writer.now()))
        }
        return null
    }

    private fun subscriptionState(tx: PaymentLedgerTx, provider: String, mode: PaymentMode, event: PushedSubscription): String? {
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        val subscription = lock(tx, provider, event.ourSubscriptionId, event.providerSubscriptionId) ?: return UNKNOWN_SUBSCRIPTION
        if (event.ourAccountId != null && event.ourAccountId != subscription.accountId) return ACCOUNT_MISMATCH
        if (subscription.providerMode != mode) return MODE_MISMATCH
        if (subscription.status == SubscriptionStatus.ENDED) return ALREADY_ENDED
        val syncedAt = subscription.providerSyncedAt
        if (syncedAt != null && event.occurredAt.isBefore(syncedAt)) return STALE_EVENT // 더 새로운 사건이 먼저 도착해 반영됐다

        var current = subscription.copy(providerSubscriptionId = subscription.providerSubscriptionId ?: event.providerSubscriptionId, providerSyncedAt = event.occurredAt)
        if (event.status == PushedSubscriptionStatus.ENDED) {
            val reason = when (current.status) {
                SubscriptionStatus.PENDING -> SubscriptionEndReason.FIRST_PAYMENT_DECLINED
                SubscriptionStatus.PAST_DUE -> SubscriptionEndReason.PAYMENT_FAILED
                else -> SubscriptionEndReason.USER_CANCELED
            }
            lifecycle.end(tx, current, reason)
            return null
        }
        if (current.status == SubscriptionStatus.PENDING) {
            current = activate(tx, lifecycle, current, event.periodStart ?: writer.now(), event.periodEnd)
        } else if (event.periodStart != null && event.periodEnd != null) {
            val moved = current.currentPeriodStart?.let { event.periodStart.isAfter(it) } ?: true
            current = current.copy(currentPeriodStart = event.periodStart, currentPeriodEnd = event.periodEnd, periodSeq = if (moved) current.periodSeq + 1 else current.periodSeq)
        }
        val target = when (event.status) {
            PushedSubscriptionStatus.ACTIVE -> SubscriptionStatus.ACTIVE
            PushedSubscriptionStatus.PAST_DUE -> SubscriptionStatus.PAST_DUE
            else -> SubscriptionStatus.CANCELING
        }
        when {
            current.status == target || SubscriptionTransitions.isAllowed(current.status, target) -> lifecycle.save(tx, current, target)
            else -> {
                // 예: 해지 예약을 되돌린 CANCELING -> ACTIVE. 전이표에 없다 — 기간 · 반영 시각만 남기고 상태는 그대로 둔다
                log.warn("a Paddle subscription event asks for a transition the ledger does not allow subscription={} from={} to={}", current.id, current.status, target)
                tx.updateSubscription(current.copy(updatedAt = writer.now()))
            }
        }
        return null
    }

    /** `PENDING` 을 첫 결제가 확인된 `ACTIVE` 로 올린다 — 이 계정의 자리(`active_key`)를 쥔다. 결제사의 기간을 그대로 쓰고 없으면 우리 주기로 센다. */
    private fun activate(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, start: java.time.Instant, end: java.time.Instant?): PaymentSubscription =
        lifecycle.save(tx, subscription, SubscriptionStatus.ACTIVE) {
            it.copy(
                anchorAt = start,
                periodSeq = 1,
                currentPeriodStart = start,
                currentPeriodEnd = end ?: SubscriptionPeriod.parse(it.period).after(start, 1),
                activeKey = it.accountId,
                attempt = 1,
            )
        }

    /** 우리 구독 id(결제창에 실어 보낸 `custom_data`) 먼저, 없으면 결제사 구독 id 로 찾아 잠근다. */
    private fun lock(tx: PaymentLedgerTx, provider: String, ourId: String?, providerId: String?): PaymentSubscription? {
        val id = ourId ?: providerId?.let { store.findSubscriptionByProviderId(provider, it)?.id } ?: return null
        return tx.lockSubscription(id)?.takeIf { it.provider == provider }
    }

    companion object {
        const val WEBHOOK = "WEBHOOK"
        const val UNKNOWN_ORDER = "UNKNOWN_ORDER"
        const val UNKNOWN_SUBSCRIPTION = "UNKNOWN_SUBSCRIPTION"
        const val ACCOUNT_MISMATCH = "ACCOUNT_MISMATCH"
        const val ORDER_MISMATCH = "ORDER_MISMATCH"
        const val MODE_MISMATCH = "MODE_MISMATCH"
        const val STALE_EVENT = "STALE_EVENT"
        const val ALREADY_ENDED = "ALREADY_ENDED"
        const val SUBSCRIPTIONS_OFF = "SUBSCRIPTIONS_OFF"
        const val NOT_HANDLED = "NOT_HANDLED"
    }
}
