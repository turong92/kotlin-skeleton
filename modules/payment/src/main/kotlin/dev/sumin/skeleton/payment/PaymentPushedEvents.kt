package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 서명된 웹훅이 곧 사실인 결제사([PaymentPushGateway])의 사건을 장부에 옮긴다 - 결제(단건 · 구독)와 구독 상태. **조회하지 않는다**: 서명이 진위를, 결제사가 말한 금액 · 통화가 사실을 보증한다.
 *
 * **무엇을 샀는지는 서버가 정했다**: 결제사의 거래는 서버가 먼저 만들었고(가격 · 수량 · 우리 줄 id 는 서버가 정한 값, [PaymentTransactionGateway]) 거래 id 가 우리 줄에 저장돼 있다.
 * 웹훅은 그 **거래 id 로만** 줄을 찾는다 - 단건은 주문의 결제 키, 구독의 첫 결제는 구독의 첫 거래 id. 구독의 갱신 · 상태 사건은 **줄에 연결된 결제사 구독 id** 로만 찾는다
 * (첫 결제가 그 id 를 연결한다). `custom_data` 는 찾는 데 쓰지 않고 교차 확인에만 쓴다. 우리 거래도 우리 구독도 아닌 사건은 반영하지 않는다.
 *
 * 모든 걸음은 호출하는 처리 작업의 한 트랜잭션 안이고 몇 번을 불러도 같은 결과다(같은 거래는 한 번, 낡은 구독 사건은 무시).
 * 돌려주는 값: null = 반영했다(또는 이미 반영돼 있다), 문자열 = 반영하지 않은 이유(수신함 줄이 `IGNORED` 로 닫히며 `last_error` 에 이 코드가 적힌다).
 * 락: 단건은 주문 줄 하나, 구독은 구독 줄 하나만 잠그고 구독 결제의 주문은 **넣기만** 한다(주문 → 구독 순서와 부딪히지 않는다).
 *
 * @param subscriptions 구독을 켠 조립에서만 - 없으면 구독 사건은 반영하지 않는다.
 */
class PaymentPushedEvents(
    private val store: PaymentLedgerStore,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val subscriptions: SubscriptionLifecycle? = null,
    private val review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun apply(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String? =
        when (event) {
            is PushedPayment -> payment(tx, gateway, event)
            is PushedSubscription -> subscriptionState(tx, gateway, event)
        }

    /**
     * 구독을 켜는 길이 계정의 자리(`active_key`) 충돌로 막혔을 때 - 되돌려진 트랜잭션 뒤에 다시 부른다. 결제는 「확인 필요」로 기록하고 결제사 구독은 해지한다.
     */
    fun applyConflicted(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions
        if (event is PushedPayment) {
            val found = store.findSubscriptionByFirstTransaction(provider, event.providerPaymentId)?.id?.let(tx::lockSubscription)
            if (found != null && lifecycle != null) unapplied(tx, lifecycle, found, event, null, cancel = false)
            cancelAtProvider(provider, event.providerSubscriptionId)
        }
        return SEAT_TAKEN
    }

    private fun payment(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment): String? {
        val provider = gateway.providerId.trim().lowercase()
        // 단건: 주문에 저장된 결제 키(= 서버가 만든 거래 id)로 찾는다
        val order = tx.findOrderByProviderPayment(provider, event.providerPaymentId)
        if (order != null) return if (order.kind == PaymentOrderKind.ONE_TIME) oneTime(tx, gateway, event, order.id) else null // 구독 결제는 이미 반영됐다(같은 거래)
        return subscriptionPayment(tx, gateway, event)
    }

    private fun oneTime(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment, orderId: String): String? {
        val order = tx.lockOrder(orderId) ?: return UNKNOWN_ORDER
        if (order.providerMode != gateway.mode) return MODE_MISMATCH
        if (order.status.isPaidFamily) return null
        // 교차 확인 - 서버가 거래에 넣은 우리 주문 id 와 다르면 반영하지 않는다
        if (event.ourOrderId != null && event.ourOrderId != order.id) return ORDER_MISMATCH
        // 금액 · 통화는 결제사가 받은 그대로다(구매자가 낸 통화) - 가격은 서버가 정한 거래의 것이다
        writer.transition(tx, order, PaymentOrderStatus.PAID, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK) {
            it.copy(
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

    private fun subscriptionPayment(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment): String? {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        // 첫 결제: 서버가 미리 만든 거래 id 가 걸린 줄. 갱신: 줄에 연결된 결제사 구독 id 의 결제(그 구독의 거래만 - 단건 거래는 구독 id 가 없다)
        val byFirst = store.findSubscriptionByFirstTransaction(provider, event.providerPaymentId)
        val id = byFirst?.id ?: event.providerSubscriptionId?.let { store.findSubscriptionByProviderId(provider, it)?.id } ?: return UNKNOWN_TRANSACTION
        val subscription = tx.lockSubscription(id)?.takeIf { it.provider == provider } ?: return UNKNOWN_TRANSACTION
        if (subscription.providerMode != gateway.mode) return MODE_MISMATCH
        if (event.ourSubscriptionId != null && event.ourSubscriptionId != subscription.id) return SUBSCRIPTION_MISMATCH // 교차 확인
        val first = byFirst != null && subscription.status == SubscriptionStatus.PENDING
        if (first && event.providerSubscriptionId == null) return NO_PROVIDER_SUBSCRIPTION // 구독의 첫 결제인데 결제사 구독 id 가 없다 - 연결할 수 없다
        return when {
            // 끝난 구독에 결제가 왔다 - 탈퇴로 끝났다면 결제사는 아직 과금 중이다
            subscription.status == SubscriptionStatus.ENDED ->
                unapplied(tx, lifecycle, subscription, event, ALREADY_ENDED, cancel = subscription.endReason == SubscriptionEndReason.ACCOUNT_ERASED)
            byFirst != null && !first -> null // 첫 거래가 이미 반영된 줄 - 같은 거래의 재전달
            else -> applyPayment(tx, lifecycle, subscription, event)
        }
    }

    private fun applyPayment(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment): String? {
        val order = paidOrder(lifecycle, subscription, event)
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
            // 갱신이 끝내 결제돼 연체가 풀린다(이 구독의 거래만 여기에 온다) - 더 새로운 구독 사건이 이미 상태를 정했다면 따르지 않는다
            subscription.status == SubscriptionStatus.PAST_DUE && !event.occurredAt.isBefore(subscription.providerSyncedAt ?: event.occurredAt) ->
                lifecycle.save(tx, base, SubscriptionStatus.ACTIVE) { it.copy(attempt = 1) }
            else -> tx.updateSubscription(base.copy(updatedAt = writer.now()))
        }
        return null
    }

    private fun paidOrder(lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment): PaymentOrder {
        val kind = if (subscription.chargeOrderId == null) PaymentOrderKind.SUBSCRIPTION_INITIAL else PaymentOrderKind.SUBSCRIPTION_RENEWAL
        return lifecycle.chargeOrder(subscription, kind, subscription.planCode, amount = event.total, dedupeKey = "${subscription.provider}:${event.providerPaymentId}").copy(
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
    }

    /**
     * 돈은 받았지만 이 구독이 받아들일 결제가 아니다 - 주문으로 기록하되 **구독은 건드리지 않고 지급 작업도 넣지 않으며** `SUBSCRIPTION_STALE_CHARGE`(확인 필요, 운영자가 환불)로 표시한다.
     * [cancel] 이면 그 결제사 구독을 즉시 해지하는 작업을 넣는다(계속 과금되지 않게). 돌려주는 값은 수신함 줄의 사유.
     */
    private fun unapplied(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment, code: String?, cancel: Boolean): String {
        val order = paidOrder(lifecycle, subscription, event).copy(reviewReason = PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE)
        if (tx.insertOrderIfAbsent(order)) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK, to = PaymentOrderStatus.PAID))
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, PaymentActor.PROVIDER, providerCode = PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE.name, from = order.status, to = order.status))
            review.flagged(order.id, PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE)
            log.warn("a provider payment was recorded but not applied order={} reason={}", order.id, code)
        }
        if (cancel) cancelAtProvider(subscription.provider, event.providerSubscriptionId)
        return code ?: SEAT_TAKEN
    }

    private fun cancelAtProvider(provider: String, providerSubscriptionId: String?) {
        if (providerSubscriptionId != null) tasks.enqueue(PaymentTask(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL, "${provider.trim().lowercase()}:$providerSubscriptionId"))
    }

    private fun subscriptionState(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedSubscription): String? {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        // 줄에 연결된 결제사 구독 id 로만 찾는다 - 연결 전(첫 결제 전)에 온 사건이나 우리 구독이 아닌 사건은 반영하지 않는다
        val id = store.findSubscriptionByProviderId(provider, event.providerSubscriptionId)?.id ?: return UNKNOWN_SUBSCRIPTION
        val subscription = tx.lockSubscription(id)?.takeIf { it.provider == provider } ?: return UNKNOWN_SUBSCRIPTION
        if (subscription.providerMode != gateway.mode) return MODE_MISMATCH
        if (subscription.status == SubscriptionStatus.ENDED) return ALREADY_ENDED
        val syncedAt = subscription.providerSyncedAt
        if (syncedAt != null && event.occurredAt.isBefore(syncedAt)) return STALE_EVENT // 더 새로운 사건이 먼저 도착해 반영됐다

        var current = subscription.copy(providerSyncedAt = event.occurredAt)
        if (event.status == PushedSubscriptionStatus.ENDED) {
            val reason = when (current.status) {
                SubscriptionStatus.PENDING -> SubscriptionEndReason.FIRST_PAYMENT_DECLINED
                SubscriptionStatus.PAST_DUE -> SubscriptionEndReason.PAYMENT_FAILED
                else -> SubscriptionEndReason.USER_CANCELED
            }
            lifecycle.end(tx, current, reason)
            return null
        }
        if (event.periodStart != null && event.periodEnd != null) {
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
                // 예: 해지 예약을 되돌린 CANCELING -> ACTIVE. 전이표에 없다 - 기간 · 반영 시각만 남기고 상태는 그대로 둔다
                log.warn("a provider subscription event asks for a transition the ledger does not allow subscription={} from={} to={}", current.id, current.status, target)
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

    companion object {
        const val WEBHOOK = "WEBHOOK"
        const val UNKNOWN_ORDER = "UNKNOWN_ORDER"
        const val UNKNOWN_SUBSCRIPTION = "UNKNOWN_SUBSCRIPTION"
        const val ORDER_MISMATCH = "ORDER_MISMATCH"
        const val MODE_MISMATCH = "MODE_MISMATCH"
        const val STALE_EVENT = "STALE_EVENT"
        const val ALREADY_ENDED = "ALREADY_ENDED"
        const val SUBSCRIPTIONS_OFF = "SUBSCRIPTIONS_OFF"
        const val UNKNOWN_TRANSACTION = "UNKNOWN_TRANSACTION"
        const val SUBSCRIPTION_MISMATCH = "SUBSCRIPTION_MISMATCH"
        const val NO_PROVIDER_SUBSCRIPTION = "NO_PROVIDER_SUB"
        const val SEAT_TAKEN = "SEAT_TAKEN"
        const val NOT_HANDLED = "NOT_HANDLED"
    }
}
