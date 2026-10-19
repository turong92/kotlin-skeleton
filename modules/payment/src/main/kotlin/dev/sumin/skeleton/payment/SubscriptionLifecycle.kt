package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.id.SortableIds
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
        // 결제사가 갱신하는 구독은 우리가 끝내도 결제사는 계속 과금한다 - 탈퇴하거나 환불로 끝나면 결제사에도 즉시 해지를 보낸다
        if (reason == SubscriptionEndReason.ACCOUNT_ERASED || reason == SubscriptionEndReason.REFUNDED) current.providerSubscriptionId?.let { tasks.enqueue(PaymentTask(PaymentTaskTypes.PROVIDER_SUBSCRIPTION_CANCEL, "${current.provider}:$it")) }
        return ended
    }

    /** 지금 과금하는 주문 한 줄 — 결제창이 없으니 승인을 선점한 `CONFIRMING` 으로 바로 만든다(`checkout_expires_at` 은 만든 시각). */
    fun chargeOrder(
        subscription: PaymentSubscription,
        kind: PaymentOrderKind,
        orderName: String,
        ids: SortableIds = SortableIds.system,
        /** 플랜 변경은 구독 금액이 아니라 변경 때 결제할 금액 · 새 플랜 코드 · 요청 키로 만든 중복 방지 키를 쓴다. */
        amount: PaymentAmount = subscription.amount,
        itemRef: String = subscription.planCode,
        dedupeKey: String = currentKey(subscription),
    ): PaymentOrder {
        val now = writer.now()
        return PaymentOrder(
            id = ids.newId(PaymentOrderIds.ORDER_PREFIX),
            accountId = subscription.accountId,
            kind = kind,
            status = PaymentOrderStatus.CONFIRMING,
            provider = subscription.provider,
            providerMode = subscription.providerMode,
            providerPaymentId = null,
            amount = amount,
            paidAmount = null,
            taxAmount = null,
            refundedAmount = 0,
            itemType = ITEM_TYPE,
            itemRef = itemRef,
            quantity = 1,
            orderName = orderName.trim().take(ORDER_NAME_MAX),
            originType = PaymentOriginType.SUBSCRIPTION,
            originRef = subscription.id,
            dedupeKey = dedupeKey,
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
            country = subscription.country,
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
        if (subscription.isProviderManaged) return // 결제사가 기간을 정한다 — 웹훅([PaymentPushedEvents])이 옮긴다
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
            order.kind == PaymentOrderKind.SUBSCRIPTION_RENEWAL && subscription.status != SubscriptionStatus.PENDING -> {
                val anchor = subscription.anchorAt ?: return stale(tx, order)
                val sequence = subscription.periodSeq + 1
                // 해지 예약 중에 이미 나간 갱신 승인이 늦게 끝나도 돈을 받았으니 그 기간만큼 늘리고 해지 예약은 그대로 둔다
                val to = if (subscription.status == SubscriptionStatus.CANCELING) SubscriptionStatus.CANCELING else SubscriptionStatus.ACTIVE
                val next = save(tx, subscription, to) {
                    it.copy(periodSeq = sequence, currentPeriodStart = period.after(anchor, sequence - 1), currentPeriodEnd = period.after(anchor, sequence), attempt = 1)
                }
                scheduleRenewal(next)
            }
            order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE && (subscription.status == SubscriptionStatus.ACTIVE || subscription.status == SubscriptionStatus.CANCELING) -> {
                val planCode = subscription.pendingPlanCode ?: order.itemRef
                val amount = subscription.pendingAmount ?: return stale(tx, order)
                val restart = properties.subscription.planChange.charge == PaymentProperties.Subscription.PlanChange.Charge.FULL_RESTART
                val changed = save(tx, subscription, subscription.status) {
                    val switched = it.copy(planCode = planCode, amount = PaymentAmount(amount, it.amount.currency), pendingPlanCode = null, pendingAmount = null)
                    // 전액 결제하고 새 주기: 지금부터 한 주기(남은 기간은 버린다). 차액만: 기간은 그대로
                    if (restart) {
                        val anchor = order.paidAt ?: now
                        switched.copy(anchorAt = anchor, periodSeq = 1, currentPeriodStart = anchor, currentPeriodEnd = period.after(anchor, 1), attempt = 1)
                    } else {
                        switched
                    }
                }
                // 변경이 도는 동안 깨어난 갱신 작업은 과금하지 않고 물러났다 — 다시 깨운다(차액만이면 기간 끝이 이미 지났을 수 있다)
                scheduleRenewal(changed)
            }
            else -> stale(tx, order)
        }
    }

    /**
     * 구독 주문이 **실패로 끝났다**(결제사의 거절 · 조회가 끝내 못 물어 죽은 조회 작업) — 주문이 끝 상태가 되는 **같은 트랜잭션**(주문 줄 락 안)에서 구독이 반응한다:
     * 첫 결제면 구독이 끝나 자리를 풀고, 갱신이면 [onChargeFailed]. **지금 과금하는 시도의 주문일 때만**(같은 주문이 두 번 알려져도 한 번 — 시도 번호가 이미 올랐으면 건너뛴다).
     */
    fun onOrderClosed(tx: PaymentLedgerTx, order: PaymentOrder) {
        val subscription = tx.lockSubscription(order.originRef ?: return) ?: return
        if (subscription.status == SubscriptionStatus.ENDED) return
        if (order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE) return onChangeClosed(tx, subscription, order)
        if (order.dedupeKey != currentKey(subscription)) return
        if (subscription.status == SubscriptionStatus.PENDING) end(tx, subscription, SubscriptionEndReason.FIRST_PAYMENT_DECLINED) else onChargeFailed(tx, subscription)
    }

    /** 변경 결제가 실패로 닫혔다 — 플랜은 그대로 두고 대기 값을 비운 뒤, 변경 때문에 물러났던 갱신 작업을 지금 다시 깨운다. */
    private fun onChangeClosed(tx: PaymentLedgerTx, subscription: PaymentSubscription, order: PaymentOrder) {
        if (subscription.chargeOrderId != order.id) return
        val cleared = save(tx, subscription, subscription.status) { it.copy(pendingPlanCode = null, pendingAmount = null) }
        scheduleRenewal(cleared, runAt = writer.now())
    }

    /**
     * 이 구독이 지금 과금하려는 주문의 `dedupe_key`. 기준 시각([PaymentSubscription.anchorAt])이 들어 있다 — 플랜 변경이 주기를 새로 시작하면(`FULL_RESTART`) 기간 번호가 1부터 다시 세어지므로
     * 이전 주기의 같은 번호와 겹치지 않게 한다.
     */
    fun currentKey(subscription: PaymentSubscription): String =
        "sub:${subscription.id}:${subscription.anchorAt?.toEpochMilli() ?: 0}:${subscription.periodSeq + 1}:${subscription.attempt}"

    private fun boughtCurrentPeriod(subscription: PaymentSubscription, order: PaymentOrder): Boolean {
        val paidAt = order.paidAt ?: return false
        val start = subscription.currentPeriodStart ?: return false
        val end = subscription.currentPeriodEnd ?: return false
        return !paidAt.isBefore(start) && paidAt.isBefore(end)
    }

    /** 과금 주문이 없거나 끝났다(실패로 닫혔거나, 돈을 받고 지급까지 마쳤거나, 전액 환불됐다) — 아직 오가는 중이 아니다. */
    fun isSettled(order: PaymentOrder?): Boolean = order == null || order.status.isClosed || order.status == PaymentOrderStatus.REFUNDED || (order.status.isPaidFamily && order.fulfilledAt != null)

    /**
     * 구독 주문이 전액 환불됐다 — 지금 과금 주문이면 구독을 바로 끝낸다(환불했는데 다음 달 또 과금되지 않게). **주문 줄 락 안에서** 불린다([PaymentRefundApplier]).
     * 지금 과금 주문이 아니거나 이미 끝난 구독이면 아무것도 하지 않는다. [keepSubscription] 이면 환불만 하고 구독은 그대로 둔다(운영자가 「구독도 해지」를 껐다).
     */
    fun onOrderRefunded(tx: PaymentLedgerTx, order: PaymentOrder, keepSubscription: Boolean = false) {
        if (keepSubscription) return
        val subscription = tx.lockSubscription(order.originRef ?: return) ?: return
        // 변경 결제의 환불은 그 기간의 구독을 끝내지 않는다(기간의 결제는 따로 있다) — 플랜은 되돌리지 않으니 운영자가 본다
        if (subscription.status == SubscriptionStatus.ENDED || order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE) return
        // 지금 과금 주문이거나(아직 지급 전), 지금 기간을 산 주문이다(그 뒤 변경 주문이 과금 주문 자리를 차지해도) - 결제 시각이 지금 기간 안에 있다
        // 「확인 필요」로 찍힌 늦은 결제(`SUBSCRIPTION_STALE_CHARGE`)는 기간을 넘기지 않았다 - 운영자가 그 이중 결제를 환불해도 기간을 낸 다른 주문의 구독은 그대로다
        if (order.reviewReason == PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE) return
        if (subscription.chargeOrderId != order.id && !boughtCurrentPeriod(subscription, order)) return
        end(tx, subscription, SubscriptionEndReason.REFUNDED)
    }

    /**
     * 갱신 과금이 확정 거절됐거나 과금 주문이 실패로 닫혔다 — 시도가 남았으면 `PAST_DUE` + 다음 시도(작업을 [PaymentProperties.Subscription.Renewal.retryInterval] 뒤로), 다 썼으면 끝낸다.
     * 구독 락 안에서 부른다.
     */
    fun onChargeFailed(tx: PaymentLedgerTx, subscription: PaymentSubscription): PaymentSubscription {
        // 해지 예약 중에 나가 있던 갱신이 거절됐다 — 갱신이 없으니 재시도 없이 끝난다
        if (subscription.status == SubscriptionStatus.CANCELING) return end(tx, subscription, SubscriptionEndReason.USER_CANCELED)
        val renewal = properties.subscription.renewal
        if (subscription.attempt >= renewal.maxAttempts) return end(tx, subscription, SubscriptionEndReason.PAYMENT_FAILED)
        val pastDue = save(tx, subscription, SubscriptionStatus.PAST_DUE) { it.copy(attempt = it.attempt + 1) }
        scheduleRenewal(pastDue, runAt = writer.now().plus(renewal.retryInterval))
        return pastDue
    }

    /** 선점한 과금 주문의 조회 작업 — 응답을 못 받아도 결제사에 주문 번호로 물어 끝낸다(승인 선점과 같은 작업). */
    fun scheduleReconcile(order: PaymentOrder) {
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_RECONCILE, order.id, runAt = writer.now().plus(properties.reconcile.firstDelay), maxAttempts = properties.reconcile.maxAttempts))
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
