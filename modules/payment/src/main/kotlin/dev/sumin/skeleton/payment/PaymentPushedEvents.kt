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
    review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val review = review
    private val flags = PaymentReviewFlags(writer, review)

    fun apply(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String? =
        when (event) {
            is PushedPayment -> if (event.ourOrderId != null) oneTime(tx, gateway, event) else subscriptionPayment(tx, gateway, event)
            is PushedSubscription -> subscriptionState(tx, gateway, event)
        }

    /**
     * 구독을 켜는 길이 계정의 자리(`active_key`) 충돌로 막혔을 때 — 되돌려진 트랜잭션 뒤에 다시 부른다. 결제는 「확인 필요」로 기록하고 결제사 구독은 해지한다.
     */
    fun applyConflicted(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPaymentEvent): String {
        val lifecycle = subscriptions
        val providerSubscriptionId = when (event) {
            is PushedPayment -> event.providerSubscriptionId
            is PushedSubscription -> event.providerSubscriptionId
        }
        if (event is PushedPayment && lifecycle != null) {
            lock(tx, gateway.providerId.trim().lowercase(), event.ourSubscriptionId, event.providerSubscriptionId)?.let { unapplied(tx, lifecycle, it, event, null) }
        }
        if (!(event is PushedSubscription && event.status == PushedSubscriptionStatus.ENDED)) cancelAtProvider(gateway.providerId, providerSubscriptionId)
        return SEAT_TAKEN
    }

    private fun oneTime(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment): String? {
        val provider = gateway.providerId.trim().lowercase()
        val order = tx.lockOrder(checkNotNull(event.ourOrderId)) ?: return UNKNOWN_ORDER
        if (order.provider != provider || order.kind != PaymentOrderKind.ONE_TIME) return UNKNOWN_ORDER
        if (order.providerMode != gateway.mode) return MODE_MISMATCH
        if (order.status.isPaidFamily) return if (order.providerPaymentId == event.providerPaymentId) null else ORDER_MISMATCH
        val owner = tx.findOrderByProviderPayment(provider, event.providerPaymentId)
        if (owner != null && owner.id != order.id) return ORDER_MISMATCH
        // 결제창 값(가격 id · customData)은 브라우저를 거쳤다 - 웹훅의 거래가 **이 주문의 상품 가격**이고 계정이 맞아야 지급한다. 아니면 지급 없이 「확인 필요」
        val context = ConvergeContext(gateway.mode, ConvergeSource.WEBHOOK, PaymentActor.PROVIDER)
        if (event.ourAccountId == null || event.ourAccountId != order.accountId) {
            flags.flag(tx, order, PaymentReviewReason.ORDER_MISMATCH, context)
            return if (event.ourAccountId == null) ACCOUNT_MISSING else ACCOUNT_MISMATCH
        }
        if (!boughtExactly(event.items, gateway.priceIdOf(order.itemRef), order.quantity)) {
            flags.flag(tx, order, PaymentReviewReason.AMOUNT_MISMATCH, context)
            return PRICE_MISMATCH
        }
        // 금액 · 통화는 결제사가 받은 그대로다 - 구매자가 낸 통화로 바꾼다
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

    private fun boughtExactly(items: List<PushedItem>, expectedPrice: String?, quantity: Int): Boolean =
        expectedPrice != null && items.size == 1 && items[0].priceId == expectedPrice && items[0].quantity == quantity

    private fun subscriptionPayment(tx: PaymentLedgerTx, gateway: PaymentPushGateway, event: PushedPayment): String? {
        val provider = gateway.providerId.trim().lowercase()
        val lifecycle = subscriptions ?: return SUBSCRIPTIONS_OFF
        val subscription = lock(tx, provider, event.ourSubscriptionId, event.providerSubscriptionId) ?: return UNKNOWN_SUBSCRIPTION
        if (subscription.providerMode != gateway.mode) return MODE_MISMATCH
        if (tx.findOrderByProviderPayment(provider, event.providerPaymentId) != null) return null // 같은 거래 - 이미 반영했다
        val linked = subscription.providerSubscriptionId
        return when {
            // 한 줄에 결제사 구독이 둘 - 같은 결제창을 두 번 결제했다. 먼저 붙은 쪽만 이 줄의 구독이다 → 둘째는 기록하고 즉시 해지
            linked != null && event.providerSubscriptionId != null && linked != event.providerSubscriptionId ->
                unapplied(tx, lifecycle, subscription, event, PROVIDER_SUBSCRIPTION_MISMATCH, cancel = true)
            // 계정은 결제창에 실어 보낸 값이다 - 아직 결제사 구독이 붙지 않은 줄(첫 결제)은 계정이 맞아야 한다
            event.ourAccountId != subscription.accountId && (event.ourAccountId != null || linked == null) ->
                unapplied(tx, lifecycle, subscription, event, if (event.ourAccountId == null) ACCOUNT_MISSING else ACCOUNT_MISMATCH, cancel = true)
            !boughtExactly(event.items, gateway.priceIdOf(subscription.planCode), 1) ->
                unapplied(tx, lifecycle, subscription, event, PRICE_MISMATCH, cancel = true)
            // 끝난 구독에 결제가 왔다 - 탈퇴로 끝났다면 결제사는 아직 과금 중이다
            subscription.status == SubscriptionStatus.ENDED ->
                unapplied(tx, lifecycle, subscription, event, ALREADY_ENDED, cancel = subscription.endReason == SubscriptionEndReason.ACCOUNT_ERASED)
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
            // 갱신이 끝내 결제돼 연체가 풀린다 - 더 새로운 구독 사건이 이미 상태를 정했다면 따르지 않는다
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
    private fun unapplied(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, subscription: PaymentSubscription, event: PushedPayment, code: String?, cancel: Boolean = true): String {
        val order = paidOrder(lifecycle, subscription, event).copy(reviewReason = PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE)
        if (tx.insertOrderIfAbsent(order)) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = WEBHOOK, to = PaymentOrderStatus.PAID))
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, PaymentActor.PROVIDER, providerCode = PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE.name, from = order.status, to = order.status))
            review.flagged(order.id, PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE)
            log.warn("a Paddle payment was recorded but not applied order={} reason={}", order.id, code)
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
        val subscription = lock(tx, provider, event.ourSubscriptionId, event.providerSubscriptionId) ?: return UNKNOWN_SUBSCRIPTION
        if (subscription.providerMode != gateway.mode) return MODE_MISMATCH
        val linked = subscription.providerSubscriptionId
        if (linked != null && linked != event.providerSubscriptionId) {
            // 이 줄에 붙은 것이 아닌 결제사 구독 - 반영하지 않고 해지한다(이미 끝난 구독의 사건이면 해지할 것이 없다)
            if (event.status != PushedSubscriptionStatus.ENDED) cancelAtProvider(provider, event.providerSubscriptionId)
            return PROVIDER_SUBSCRIPTION_MISMATCH
        }
        if (event.ourAccountId != subscription.accountId && (event.ourAccountId != null || linked == null)) return if (event.ourAccountId == null) ACCOUNT_MISSING else ACCOUNT_MISMATCH
        if (subscription.status == SubscriptionStatus.ENDED) return ALREADY_ENDED
        val syncedAt = subscription.providerSyncedAt
        if (syncedAt != null && event.occurredAt.isBefore(syncedAt)) return STALE_EVENT // 더 새로운 사건이 먼저 도착해 반영됐다

        var current = subscription.copy(providerSubscriptionId = linked ?: event.providerSubscriptionId, providerSyncedAt = event.occurredAt)
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
                // 예: 해지 예약을 되돌린 CANCELING -> ACTIVE. 전이표에 없다 - 기간 · 반영 시각만 남기고 상태는 그대로 둔다
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
        const val ACCOUNT_MISSING = "ACCOUNT_MISSING"
        const val PRICE_MISMATCH = "PRICE_MISMATCH"
        const val PROVIDER_SUBSCRIPTION_MISMATCH = "PROVIDER_SUB_MISMATCH"
        const val SEAT_TAKEN = "SEAT_TAKEN"
        const val NOT_HANDLED = "NOT_HANDLED"
    }
}
