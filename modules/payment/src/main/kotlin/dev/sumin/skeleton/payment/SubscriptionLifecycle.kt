package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/**
 * 구독 줄을 바꾸는 한 곳 — 전이표([SubscriptionTransitions]) 검사 · 기간 넘기기 · 끝내기 · 과금 주문 만들기(스펙 `2026-10-09-subscription-design.md` §3 · §5).
 * 호출하는 쪽이 구독 줄을 [PaymentLedgerTx.lockSubscription] 으로 잠그고 **다시 읽은 값**을 넘긴다.
 */
class SubscriptionLifecycle(
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
    review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val flags = PaymentReviewFlags(writer, review)

    /** 상태를 [to] 로 옮기고 [mutate] 로 나머지 칸을 바꿔 저장한다. 표에 없는 전이는 [IllegalStateException]. */
    fun save(
        tx: PaymentLedgerTx,
        current: PaymentSubscription,
        to: SubscriptionStatus,
        mutate: (PaymentSubscription) -> PaymentSubscription = { it },
    ): PaymentSubscription {
        SubscriptionTransitions.require(current.status, to)
        val changed = mutate(current).copy(status = to, updatedAt = writer.now())
        tx.updateSubscription(changed)
        return changed
    }

    /**
     * 구독을 끝낸다. 자리(`active_key`)를 풀고 카드 표시 두 칸을 지운다. 빌링키가 있으면 삭제 작업을 넣는다 — 빌링키 · 고객 키는 **결제사에서 지운 뒤에** 비워진다
     * ([SubscriptionRevokeTaskHandler]; 지우기 전에 비우면 결제사에 살아 있는 카드를 아무도 못 지운다).
     */
    fun end(tx: PaymentLedgerTx, current: PaymentSubscription, reason: SubscriptionEndReason): PaymentSubscription {
        val now = writer.now()
        val hasToken = current.billingToken != null
        val ended = save(tx, current, SubscriptionStatus.ENDED) {
            it.copy(
                activeKey = null,
                cardIssuer = null,
                cardLast4 = null,
                customerKey = if (hasToken) it.customerKey else null,
                endReason = reason,
                endedAt = now,
                accountErasedAt = if (reason == SubscriptionEndReason.ACCOUNT_ERASED) now else it.accountErasedAt,
            )
        }
        if (hasToken) tasks.enqueue(PaymentTask(PaymentTaskTypes.SUBSCRIPTION_REVOKE, current.id))
        return ended
    }

    /** 지금 과금하는 주문 한 줄 — 결제창이 없으니 승인을 선점한 `CONFIRMING` 으로 바로 만든다(`checkout_expires_at` 은 만든 시각). */
    fun chargeOrder(subscription: PaymentSubscription, kind: PaymentOrderKind, orderName: String, random: java.util.Random = java.security.SecureRandom()): PaymentOrder {
        val now = writer.now()
        val sequence = subscription.periodSeq + 1
        return PaymentOrder(
            id = PaymentOrderIds.newOrderId(random),
            accountId = subscription.accountId,
            kind = kind,
            status = PaymentOrderStatus.CONFIRMING,
            provider = subscription.provider,
            providerMode = subscription.providerMode,
            providerPaymentId = null,
            amount = subscription.amount,
            paidAmount = null,
            taxAmount = null,
            refundedAmount = 0,
            itemType = ITEM_TYPE,
            itemRef = subscription.planCode,
            quantity = 1,
            orderName = orderName.trim().take(ORDER_NAME_MAX),
            originType = PaymentOriginType.SUBSCRIPTION,
            originRef = subscription.id,
            dedupeKey = "sub:${subscription.id}:$sequence:${subscription.attempt}",
            method = null,
            cardIssuer = null,
            cardLast4 = null,
            failureCode = null,
            reviewReason = null,
            checkoutExpiresAt = now,
            confirmStartedAt = now,
            paidAt = null,
            fulfilledAt = null,
            closedAt = null,
            accountErasedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 과금 주문을 넣은 이력 — `CONFIRM_STARTED`(결제창 없이 곧바로 선점). */
    fun chargeStarted(tx: PaymentLedgerTx, order: PaymentOrder, actor: PaymentActor, actorId: String? = null) {
        tx.appendEvent(writer.event(order.id, PaymentEventType.CONFIRM_STARTED, actor, actorId, to = order.status))
    }

    /**
     * 구독 주문이 `PAID` 가 되었다 — **지급 고리**([PaymentFulfillment.fulfil])에서 **주문 줄 락 안에서 한 번** 불린다. 기간은 여기서만 넘어간다.
     * 구독의 지금 과금 주문([PaymentSubscription.chargeOrderId])이 아닌 주문이 늦게 `PAID` 가 되었거나 구독이 이미 끝났으면 기간은 그대로 두고 그 주문에 「확인 필요」를 찍는다(운영자가 환불).
     */
    fun onOrderPaid(tx: PaymentLedgerTx, order: PaymentOrder) {
        val subscription = tx.lockSubscription(order.originRef ?: return) ?: return
        val current = subscription.chargeOrderId == order.id && subscription.status != SubscriptionStatus.ENDED
        if (!current) return stale(tx, order)
        val period = SubscriptionPeriod.parse(subscription.period)
        val now = writer.now()
        when {
            order.kind == PaymentOrderKind.SUBSCRIPTION_INITIAL && subscription.status == SubscriptionStatus.PENDING -> {
                val anchor = order.paidAt ?: now
                val active = save(tx, subscription, SubscriptionStatus.ACTIVE) {
                    it.copy(anchorAt = anchor, periodSeq = 1, currentPeriodStart = anchor, currentPeriodEnd = period.after(anchor, 1), attempt = 1)
                }
                scheduleRenewal(active)
            }
            else -> stale(tx, order)
        }
    }

    /** 기간 끝에 깨어나는 작업을 넣는다 — 갱신 과금 · 해지 예약의 종료 · 재시도가 모두 이 작업이다. */
    fun scheduleRenewal(subscription: PaymentSubscription, runAt: java.time.Instant? = subscription.currentPeriodEnd) {
        tasks.enqueue(PaymentTask(PaymentTaskTypes.SUBSCRIPTION_RENEW, subscription.id, runAt = runAt, maxAttempts = properties.reconcile.maxAttempts))
    }

    private fun stale(tx: PaymentLedgerTx, order: PaymentOrder) {
        log.warn("a subscription order was paid but is not the charge the subscription is waiting for order={}", order.id)
        flags.flag(tx, order, PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE, ConvergeContext(order.providerMode, ConvergeSource.RECONCILE))
    }

    companion object {
        const val ITEM_TYPE = "subscription"
        private const val ORDER_NAME_MAX = 100
    }
}
