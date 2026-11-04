package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.id.SortableIds
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 계정마다 하나인 스토어 계정 토큰(애플 `appAccountToken` · 구글 `obfuscatedAccountId`) - 계정 id 의 해시라 개인정보도 비밀도 아니다.
 * 설정 응답이 이 값을 앱에 주고, 앱이 구매에 박아 오면 [PaymentStorePurchases.record] 가 부른 계정의 것과 맞춘다.
 */
object StoreAccountTokens {
    fun of(accountId: String): String = UUID.nameUUIDFromBytes("payment-iap:$accountId".toByteArray(Charsets.UTF_8)).toString()
}

/** 스토어 상품이 우리 쪽에서 무엇인가(상품 지도의 한 줄을 푼 것) - 단건은 품목, 구독은 플랜. */
sealed interface StoreProduct {
    /** 구매의 [StorePurchaseKind] 와 짝이다. */
    val kind: StorePurchaseKind

    data class OneTime(val itemType: String, val itemRef: String, val orderName: String) : StoreProduct {
        override val kind: StorePurchaseKind get() = StorePurchaseKind.ONE_TIME
    }

    data class Subscription(val plan: SubscriptionPlan) : StoreProduct {
        override val kind: StorePurchaseKind get() = StorePurchaseKind.SUBSCRIPTION
    }
}

enum class StoreRecordOutcome {
    /** 이번 호출이 장부에 넣었다(주문 · 구독 줄, 또는 구독의 새 기간 주문). */
    RECORDED,

    /** 이미 장부에 있고 부른 계정의 것이다 - 바뀐 것이 없다(구독이면 상태 · 기간은 스토어 값에 맞췄다). */
    ALREADY_RECORDED,

    /** 스토어가 아직 끝내지 않았거나(보류) 효력이 없다(환불 · 취소 · 이미 끝난 구독) - 아무것도 쓰지 않았다. */
    NOT_PURCHASED,

    /** 다른 계정의 구매다(계정 토큰이 다르거나, 토큰 없이 다른 계정이 먼저 확인받았다) - 아무것도 쓰지 않았다. HTTP 에서는 409. */
    OWNED_BY_ANOTHER_ACCOUNT,

    /** 이 계정에 이미 살아 있는 구독이 있다 - 돈은 장부에 「확인 필요」([PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN])로 남고 지급은 없다. */
    ALREADY_SUBSCRIBED,

    /** 장부의 이 구독 줄은 이미 끝났고 되살릴 수 없다(환불 · 탈퇴로 끝났거나, 스토어도 끝난 구독이라 하거나, 새 기간의 결제가 없다). 스토어가 활성이라 하는 만료 뒤 재구독은 줄을 되살려 [RECORDED]. */
    SUBSCRIPTION_ENDED,
}

/** [orderId] · [subscriptionId] 는 이 구매가 가리키는 장부 줄(있을 때). */
data class StoreRecordResult(val outcome: StoreRecordOutcome, val orderId: String? = null, val subscriptionId: String? = null)

/**
 * 스토어가 알려 준 구매([StorePurchase] - 스토어에 직접 물어 얻은 사실)를 한 트랜잭션으로 장부에 넣는다(`PaymentPushedEvents` 의 옆 길).
 *
 * - **단건**: 주문을 `PENDING → PAID` 로 바로 넣고 지급 작업을 넣는다. 지급은 기존대로 주문 줄 락 안 `fulfilled_at` 한 번 - 같은 구매가 몇 번 · 동시에 와도 주문 한 줄, `onPaid` 한 번.
 * - **구독**: 스토어가 갱신하는 구독 줄(`provider_subscription_id` = 애플 `originalTransactionId` / 구글 구매 토큰의 해시)과 지금 기간의 주문 하나. 이미 있는 줄이면 상태 · 기간을 스토어 값에 맞추고
 *   지금 기간의 주문이 없으면 그것만 더한다(「구매 복원」). 지난 갱신은 거슬러 넣지 않는다. 구글의 새 토큰이 우리 줄을 가리키면([StorePurchase.replacesSubscriptionId]) 같은 트랜잭션에서 옛 줄을 `REPLACED` 로 끝낸다.
 * - **주인**: 구매에 계정 토큰이 있으면 부른 계정의 것([StoreAccountTokens])과 같아야 한다. 없으면 장부에 처음 넣은 계정이 주인이다. 아니면 [StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT].
 * - **모드**: 주문 · 구독 줄의 모드는 구매의 [StorePurchase.mode] 다 - 테스트 결제는 `TEST` 로 박혀 대시보드 매출에서 빠진다.
 * - **자리 충돌**: 이미 살아 있는 구독이 있는 계정이 구독을 샀다면 돈은 장부에(주문 `PAID` + `SUBSCRIPTION_SEAT_TAKEN`) 남기고 지급은 하지 않는다. 구독 줄은 `PENDING`(자리 없음)으로 남아 이후 사건이 같은 줄을 찾는다.
 *
 * 스토어 호출은 이 안에 없다([PaymentStoreGateway.fetch] 는 호출한 쪽이 락 밖에서 했다). 지급이 커밋된 뒤에야 [PaymentStoreGateway.afterGrant] 를 한 번 부르고(실패해도 지급은 그대로), 주문 지급을 한 번 시도한다
 * (실패하면 지급 작업이 재시도한다). 동시에 같은 구매가 오면 진 쪽은 다시 읽어 [StoreRecordOutcome.ALREADY_RECORDED] 를 받는다.
 *
 * @param lifecycle 구독을 켠 조립에서만 - 없으면 구독 구매는 [IllegalStateException].
 * @param fulfillment 있으면 커밋 직후 지급을 한 번 시도한다(없으면 지급 작업만).
 */
class PaymentStorePurchases(
    private val store: PaymentLedgerStore,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val pushed: PaymentPushedEvents,
    private val lifecycle: SubscriptionLifecycle? = null,
    private val review: PaymentReviewNotifier = PaymentReviewNotifier.NONE,
    private val fulfillment: PaymentFulfillment? = null,
    private val ids: SortableIds = SortableIds.system,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 한 시도의 결과 - [granted] 는 이번 트랜잭션이 새로 지급을 걸어 둔 주문 id(커밋 뒤에 지급을 시도하고 승인 통지를 한다). */
    private class Attempt(val result: StoreRecordResult, val granted: String? = null)

    /**
     * @param gateway 이 구매를 확인한 스토어 결제사 - 장부의 결제사 id 가 된다.
     * @param purchase [PaymentStoreGateway.fetch] 가 돌려준 값. 앱이 보낸 값이 아니다.
     * @param product 상품 지도로 푼 우리 쪽 상품 - 구매의 종류와 같아야 한다([IllegalArgumentException]).
     */
    fun record(gateway: PaymentStoreGateway, accountId: String, purchase: StorePurchase, product: StoreProduct): StoreRecordResult {
        require(purchase.kind == product.kind) { "a ${purchase.kind} purchase cannot be recorded as a ${product.kind} product" }
        if (purchase.state != StorePurchaseState.PURCHASED) return StoreRecordResult(StoreRecordOutcome.NOT_PURCHASED)
        if (purchase.accountToken?.equals(StoreAccountTokens.of(accountId), ignoreCase = true) == false) return StoreRecordResult(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT)
        val provider = gateway.providerId.trim().lowercase()
        repeat(MAX_ATTEMPTS) {
            // 동시에 같은 구매를 넣은 쪽에 졌거나(null) 자리 유니크에 걸렸으면(다른 구독이 그사이 자리를 쥐었다) 새 트랜잭션에서 다시 읽고 판단한다
            val attempt = try {
                store.inTransaction { tx ->
                    when (product) {
                        is StoreProduct.OneTime -> oneTime(tx, provider, accountId, purchase, product)
                        is StoreProduct.Subscription -> subscription(tx, provider, accountId, purchase, product.plan)
                    }
                }
            } catch (e: PaymentLedgerConflictException) {
                null
            }
            if (attempt != null) {
                attempt.granted?.let { afterCommit(gateway, purchase, it) }
                return attempt.result
            }
        }
        error("the store purchase could not be recorded: the ledger kept changing under it")
    }

    private fun afterCommit(gateway: PaymentStoreGateway, purchase: StorePurchase, orderId: String) {
        fulfillment?.tryFulfil(orderId)
        try {
            gateway.afterGrant(purchase)
        } catch (e: Exception) {
            if (e is InterruptedException) throw e
            log.warn("the store could not be told about a grant order={} exception={}", orderId, e.javaClass.simpleName)
        }
    }

    private fun oneTime(tx: PaymentLedgerTx, provider: String, accountId: String, purchase: StorePurchase, product: StoreProduct.OneTime): Attempt? {
        tx.findOrderByProviderPayment(provider, purchase.providerPaymentId)?.let { return known(it, accountId) }
        val now = writer.now()
        val order = PaymentOrder(
            id = ids.newId(PaymentOrderIds.ORDER_PREFIX),
            accountId = accountId,
            kind = PaymentOrderKind.ONE_TIME,
            status = PaymentOrderStatus.PENDING,
            provider = provider,
            providerMode = purchase.mode,
            providerPaymentId = purchase.providerPaymentId,
            amount = purchase.amount,
            paidAmount = null,
            taxAmount = null,
            refundedAmount = 0,
            itemType = product.itemType,
            itemRef = product.itemRef,
            quantity = purchase.quantity,
            orderName = product.orderName.trim().take(ORDER_NAME_MAX),
            originType = PaymentOriginType.CHECKOUT,
            originRef = null,
            dedupeKey = dedupeKey(provider, purchase.providerPaymentId),
            method = null,
            cardIssuer = null,
            cardLast4 = null,
            failureCode = null,
            reviewReason = null,
            checkoutExpiresAt = now,
            confirmStartedAt = null,
            paidAt = null,
            fulfilledAt = null,
            closedAt = null,
            accountErasedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        if (!tx.insertOrderIfAbsent(order)) return null
        tx.appendEvent(writer.event(order.id, PaymentEventType.ORDER_CREATED, PaymentActor.USER, accountId, to = order.status))
        writer.transition(tx, order, PaymentOrderStatus.PAID, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = STORE) {
            it.copy(paidAmount = purchase.amount.amount, paidAt = purchase.paidAt)
        }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        return Attempt(StoreRecordResult(StoreRecordOutcome.RECORDED, orderId = order.id), granted = order.id)
    }

    private fun known(order: PaymentOrder, accountId: String): Attempt =
        Attempt(
            StoreRecordResult(if (order.accountId == accountId) StoreRecordOutcome.ALREADY_RECORDED else StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT, orderId = order.id.takeIf { order.accountId == accountId }),
        )

    private fun subscription(tx: PaymentLedgerTx, provider: String, accountId: String, purchase: StorePurchase, plan: SubscriptionPlan): Attempt? {
        val lifecycle = checkNotNull(lifecycle) { "store subscriptions need the subscription feature (skeleton.payment.subscription.enabled=true)" }
        val providerSubscriptionId = requireNotNull(purchase.providerSubscriptionId) { "a store subscription purchase carries its subscription id" }
        store.findSubscriptionByProviderId(provider, providerSubscriptionId)?.let { return restore(tx, lifecycle, provider, accountId, purchase, it) }
        if (purchase.subscriptionStatus == PushedSubscriptionStatus.ENDED) return Attempt(StoreRecordResult(StoreRecordOutcome.NOT_PURCHASED))

        // 구글의 새 토큰이 우리 줄을 가리킨다(올리기 · 다시 구독) - 같은 트랜잭션에서 옛 줄을 끝내 자리를 풀고 새 줄을 연다
        val replaced = purchase.replacesSubscriptionId
            ?.let { store.findSubscriptionByProviderId(provider, it) }
            ?.takeIf { it.accountId == accountId && it.status != SubscriptionStatus.ENDED }
            ?.let { tx.lockSubscription(it.id) }
        if (replaced != null && replaced.status != SubscriptionStatus.ENDED) lifecycle.end(tx, replaced, SubscriptionEndReason.REPLACED)
        val seatTaken = store.findLiveSubscription(accountId)?.let { it.id != replaced?.id } ?: false

        val now = writer.now()
        val line = PaymentSubscription(
            id = ids.newId(PaymentOrderIds.SUBSCRIPTION_PREFIX),
            accountId = accountId,
            provider = provider,
            providerMode = purchase.mode,
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
            customerKey = PaymentOrderIds.newCustomerKey(),
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
            dedupeKey = dedupeKey(provider, providerSubscriptionId),
            providerSubscriptionId = providerSubscriptionId,
            providerSyncedAt = purchase.paidAt,
            regularMonthlyAmount = lifecycle.regularMonthlyOf(plan),
        )
        if (!tx.insertSubscriptionIfAbsent(line)) return null
        val order = paidOrder(lifecycle, line, purchase, plan).let { if (seatTaken) it.copy(reviewReason = PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN) else it }
        if (!tx.insertOrderIfAbsent(order)) return null
        tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = STORE, to = PaymentOrderStatus.PAID))
        if (seatTaken) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, PaymentActor.PROVIDER, providerCode = PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN.name, from = order.status, to = order.status))
            review.flagged(order.id, PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN)
            log.warn("a store subscription was paid for but the account already has a live one order={}", order.id)
            return Attempt(StoreRecordResult(StoreRecordOutcome.ALREADY_SUBSCRIBED, orderId = order.id, subscriptionId = line.id))
        }
        val start = purchase.periodStart ?: purchase.paidAt
        val active = lifecycle.save(tx, line, SubscriptionStatus.ACTIVE) {
            it.copy(
                anchorAt = start,
                periodSeq = 1,
                currentPeriodStart = start,
                currentPeriodEnd = purchase.periodEnd ?: SubscriptionPeriod.parse(it.period).after(start, 1),
                activeKey = accountId,
                chargeOrderId = order.id,
            )
        }
        when (purchase.subscriptionStatus) {
            PushedSubscriptionStatus.CANCELING -> lifecycle.save(tx, active, SubscriptionStatus.CANCELING)
            PushedSubscriptionStatus.PAST_DUE -> lifecycle.save(tx, active, SubscriptionStatus.PAST_DUE)
            else -> Unit
        }
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        return Attempt(StoreRecordResult(StoreRecordOutcome.RECORDED, orderId = order.id, subscriptionId = line.id), granted = order.id)
    }

    /** 장부에 이미 있는 구독 줄 - 주인을 확인하고 스토어의 지금 상태 · 기간에 맞춘다. 지금 기간의 주문이 없으면 그것만 더한다. */
    private fun restore(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, provider: String, accountId: String, purchase: StorePurchase, existing: PaymentSubscription): Attempt {
        val other = Attempt(StoreRecordResult(StoreRecordOutcome.OWNED_BY_ANOTHER_ACCOUNT))
        if (existing.accountId != accountId) return other
        // 끝난 줄에 웹훅이 먼저 넣은 이 결제의 주문(`SUBSCRIPTION_STALE_CHARGE`)이 있으면 그것을 먼저 잠근다(주문 → 구독 순서)
        val pushedFirst = if (existing.status == SubscriptionStatus.ENDED) tx.findOrderByProviderPayment(provider, purchase.providerPaymentId)?.takeIf { adoptable(it, existing) }?.let { tx.lockOrder(it.id) } else null
        val locked = checkNotNull(tx.lockSubscription(existing.id)) { "the subscription line disappeared" }
        val result = { outcome: StoreRecordOutcome, orderId: String? -> StoreRecordResult(outcome, orderId = orderId, subscriptionId = locked.id) }
        if (locked.status == SubscriptionStatus.ENDED) {
            // 만료 뒤 다시 구독한 사람 - 애플은 같은 구독 그룹이면 같은 originalTransactionId 라 같은 줄로 온다. 스토어가 지금 활성이고 새 기간의 결제가 있으면 줄을 되살린다
            val adopted = pushedFirst?.takeIf { adoptable(it, locked) }
            if (!revivable(locked, purchase) || (adopted == null && tx.findOrderByProviderPayment(provider, purchase.providerPaymentId) != null)) return Attempt(result(StoreRecordOutcome.SUBSCRIPTION_ENDED, null))
            return revive(tx, lifecycle, accountId, purchase, locked, adopted)
        }
        // 자리가 막혀 켜지 못한 줄 - 돈은 이미 장부에 있고 켜는 길은 운영자의 몫이다
        if (locked.status == SubscriptionStatus.PENDING) return Attempt(result(StoreRecordOutcome.ALREADY_SUBSCRIBED, tx.findOrderByProviderPayment(provider, purchase.providerPaymentId)?.id))

        var current = locked
        var recorded: PaymentOrder? = null
        val plan = lifecycle.plans()?.find(locked.planCode)
        if (tx.findOrderByProviderPayment(provider, purchase.providerPaymentId) == null) {
            val order = paidOrder(lifecycle, locked, purchase, plan)
            if (tx.insertOrderIfAbsent(order)) {
                tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = STORE, to = PaymentOrderStatus.PAID))
                tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
                current = locked.copy(chargeOrderId = order.id, updatedAt = writer.now()).also(tx::updateSubscription)
                recorded = order
            }
        }
        val state = PushedSubscription(
            eventId = "store-confirm", occurredAt = current.providerSyncedAt ?: purchase.paidAt, providerSubscriptionId = checkNotNull(current.providerSubscriptionId),
            status = purchase.subscriptionStatus ?: PushedSubscriptionStatus.ACTIVE, periodStart = purchase.periodStart, periodEnd = purchase.periodEnd, mode = purchase.mode,
        )
        pushed.applySubscriptionState(tx, lifecycle, current, state, purchase.mode) { null }?.let { log.info("the store state was not applied to the subscription line reason={}", it) }
        return if (recorded != null) Attempt(result(StoreRecordOutcome.RECORDED, recorded.id), granted = recorded.id) else Attempt(result(StoreRecordOutcome.ALREADY_RECORDED, tx.findOrderByProviderPayment(provider, purchase.providerPaymentId)?.id))
    }

    /** 웹훅이 이 끝난 줄에 먼저 넣어 둔 「확인 필요」 주문인가 - 되살리기가 받아 쓸 수 있다. */
    private fun adoptable(order: PaymentOrder, line: PaymentSubscription): Boolean =
        order.originRef == line.id && order.accountId == line.accountId && order.status == PaymentOrderStatus.PAID && order.reviewReason == PaymentReviewReason.SUBSCRIPTION_STALE_CHARGE

    /** 끝난 줄을 되살릴 수 있는가 - 스토어가 지금 활성이고, 끝난 이유가 환불 · 회수나 탈퇴가 아니다(그건 스토어가 활성이라 해도 되살리지 않는다). */
    private fun revivable(ended: PaymentSubscription, purchase: StorePurchase): Boolean =
        purchase.subscriptionStatus.let { it == null || it == PushedSubscriptionStatus.ACTIVE || it == PushedSubscriptionStatus.CANCELING } &&
            ended.endReason != SubscriptionEndReason.REFUNDED && ended.endReason != SubscriptionEndReason.ACCOUNT_ERASED

    /**
     * 끝난 줄을 새 기간으로 되살린다 - 새 기간 주문을 넣고 줄을 `ACTIVE` 로(전이표에 없는 길이라 줄을 직접 고친다). 이 계정에 다른 살아 있는 구독이 있으면 되살리지 않고
     * 돈만 장부에 남긴다(첫 구독의 자리 충돌과 같다).
     */
    private fun revive(tx: PaymentLedgerTx, lifecycle: SubscriptionLifecycle, accountId: String, purchase: StorePurchase, ended: PaymentSubscription, adopted: PaymentOrder?): Attempt {
        val result = { outcome: StoreRecordOutcome, orderId: String? -> StoreRecordResult(outcome, orderId = orderId, subscriptionId = ended.id) }
        val seatTaken = store.findLiveSubscription(accountId) != null
        // 웹훅이 이 결제를 먼저 끝난 줄에 「확인 필요」 주문으로 넣어 두었다면 새 주문을 넣지 않고 그 주문의 표시를 풀어 쓴다 - 자리가 막혔으면 표시를 그대로 둔다(운영자가 환불)
        if (adopted != null && seatTaken) return Attempt(result(StoreRecordOutcome.ALREADY_SUBSCRIBED, adopted.id))
        val order = adopted
            ?: paidOrder(lifecycle, ended, purchase, lifecycle.plans()?.find(ended.planCode)).let { if (seatTaken) it.copy(reviewReason = PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN) else it }
        if (adopted != null) {
            writer.touch(tx, adopted, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, null, "STORE_RESUBSCRIBED") { it.copy(reviewReason = null) }
        } else {
            if (!tx.insertOrderIfAbsent(order)) return Attempt(result(StoreRecordOutcome.ALREADY_RECORDED, null))
            tx.appendEvent(writer.event(order.id, PaymentEventType.CONVERGED, PaymentActor.PROVIDER, providerCode = STORE, to = PaymentOrderStatus.PAID))
        }
        if (seatTaken) {
            tx.appendEvent(writer.event(order.id, PaymentEventType.REVIEW_FLAGGED, PaymentActor.PROVIDER, providerCode = PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN.name, from = order.status, to = order.status))
            review.flagged(order.id, PaymentReviewReason.SUBSCRIPTION_SEAT_TAKEN)
            log.warn("a store subscription came back after it ended but the account already has a live one order={}", order.id)
            return Attempt(result(StoreRecordOutcome.ALREADY_SUBSCRIBED, order.id))
        }
        val start = purchase.periodStart ?: purchase.paidAt
        val now = writer.now()
        val revived = ended.copy(
            status = SubscriptionStatus.ACTIVE, activeKey = accountId, endReason = null, endedAt = null, chargeOrderId = order.id, providerSyncedAt = purchase.paidAt,
            currentPeriodStart = start, currentPeriodEnd = purchase.periodEnd ?: SubscriptionPeriod.parse(ended.period).after(start, 1), periodSeq = ended.periodSeq + 1, updatedAt = now,
        )
        tx.updateSubscription(revived)
        if (purchase.subscriptionStatus == PushedSubscriptionStatus.CANCELING) lifecycle.save(tx, revived, SubscriptionStatus.CANCELING)
        tasks.enqueue(PaymentTask(PaymentTaskTypes.ORDER_FULFIL, order.id))
        return Attempt(result(StoreRecordOutcome.RECORDED, order.id), granted = order.id)
    }

    /** 스토어가 받은 이 기간의 돈 - 정기 금액이 아니라 스토어 금액 그대로(무료 체험은 0원)이고, 이미 `PAID` 로 시작한다. */
    private fun paidOrder(lifecycle: SubscriptionLifecycle, line: PaymentSubscription, purchase: StorePurchase, plan: SubscriptionPlan?): PaymentOrder {
        val kind = if (line.chargeOrderId == null) PaymentOrderKind.SUBSCRIPTION_INITIAL else PaymentOrderKind.SUBSCRIPTION_RENEWAL
        return lifecycle.chargeOrder(
            line, kind, plan?.name ?: line.planCode, amount = purchase.amount, itemRef = line.planCode, dedupeKey = dedupeKey(line.provider, purchase.providerPaymentId),
        ).copy(
            status = PaymentOrderStatus.PAID,
            providerPaymentId = purchase.providerPaymentId,
            paidAmount = purchase.amount.amount,
            confirmStartedAt = null,
            checkoutExpiresAt = purchase.paidAt,
            paidAt = purchase.paidAt,
        )
    }

    /** 같은 거래 · 같은 구독이 몇 번 와도 한 줄이게 하는 유니크 열의 값(해시라 구글의 긴 번호도 열에 들어간다). */
    private fun dedupeKey(provider: String, reference: String) = PaymentOrderIds.dedupeKey("store:$provider", IdempotencyKey(reference))

    companion object {
        /** 이력의 `provider_code`: 스토어가 확인한 구매로 넣었다. */
        const val STORE = "STORE"
        private const val MAX_ATTEMPTS = 3
        private const val ORDER_NAME_MAX = 100
    }
}
