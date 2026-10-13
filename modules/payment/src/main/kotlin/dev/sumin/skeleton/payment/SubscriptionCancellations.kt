package dev.sumin.skeleton.payment

import org.slf4j.LoggerFactory

/** 중도 해지 미리보기 — [quote] 의 [CancelQuote.open] 이 false 면 이 구독은 기간 끝 해지만 된다. */
data class CancelPreview(val subscription: PaymentSubscription, val quote: CancelQuote)

/** 주문 하나의 환불 결과. [NOT_SENT] 는 결제사에 보내지 못했다(작업이 다시 보낸다), [FAILED] 는 결제사가 거절했다(사람이 본다). */
enum class CancelRefundStatus { SUCCEEDED, PENDING, FAILED, NOT_SENT }

data class CancelRefundResult(val orderId: String, val amount: Long, val status: CancelRefundStatus)

data class CancelNowResult(val subscription: PaymentSubscription, val quote: CancelQuote, val refunds: List<CancelRefundResult>)

/**
 * 구독 중도 해지(스펙 `2026-10-12-subscription-cancel-refund-design.md`) — 미리보기와 실행. 정책은 구독의 나라가 고른다([SubscriptionCancelPolicies]).
 *
 * 실행은 **끝내기 → 환불** 순서다: 구독 줄을 잠그고(갱신 과금 중이면 [PaymentSubscriptionChargeInProgressException]) 정책이 열려 있으면 그 트랜잭션에서 구독을 `ENDED`(`USER_CANCELED_NOW`)로 끝내고
 * 빌링키 삭제 작업과 환불 재시도 작업을 넣는다. 그 뒤 이번 기간에 낸 주문마다 기존 환불 장부로 환불을 보낸다. 환불 줄의 멱등 키가 (구독, 주문)이라 **두 번 눌러도 · 작업이 다시 돌아도 환불은 한 번**이다.
 * 금액은 끝난 시각([PaymentSubscription.endedAt]) 기준으로 다시 계산하므로 처음 누른 때나 다시 부른 때나 같다. 환불 합계는 이번 기간에 낸 돈(이미 돌려받은 몫 뺀 것)을 넘지 않는다.
 * 결제사를 부르므로 **바깥 트랜잭션 안에서 부르면 던진다**.
 */
class SubscriptionCancellations(
    private val store: PaymentLedgerStore,
    private val plans: SubscriptionPlans,
    private val policies: SubscriptionCancelPolicies,
    private val usage: SubscriptionUsageCheck,
    private val lifecycle: SubscriptionLifecycle,
    private val refunds: PaymentRefunds,
    private val convergence: PaymentConvergence,
    private val tasks: PaymentTasks,
    private val writer: PaymentLedgerWriter,
    private val properties: PaymentProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun preview(accountId: String, subscriptionId: String): CancelPreview {
        val subscription = owned(store.findSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
        if (!subscription.status.isInUse) throw PaymentSubscriptionCancelNotAllowedException()
        return CancelPreview(subscription, quote(subscription, writer.now()))
    }

    fun cancelNow(accountId: String, subscriptionId: String): CancelNowResult {
        store.requireNoOpenTransaction("SubscriptionCancellations.cancelNow")
        val ended = store.inTransaction { tx ->
            val locked = owned(tx.lockSubscription(subscriptionId), accountId) ?: throw PaymentSubscriptionNotFoundException()
            if (locked.status == SubscriptionStatus.ENDED && locked.endReason == SubscriptionEndReason.USER_CANCELED_NOW) return@inTransaction locked // 같은 요청의 재전송
            if (!locked.status.isInUse) throw PaymentSubscriptionCancelNotAllowedException()
            if (!lifecycle.isSettled(locked.chargeOrderId?.let(store::findOrder))) throw PaymentSubscriptionChargeInProgressException()
            val now = writer.now()
            if (!quote(locked, now).open) throw PaymentSubscriptionCancelNotAllowedException()
            val done = lifecycle.end(tx, locked, SubscriptionEndReason.USER_CANCELED_NOW)
            tasks.enqueue(PaymentTask(PaymentTaskTypes.SUBSCRIPTION_CANCEL_REFUND, locked.id, runAt = now.plus(properties.reconcile.firstDelay), maxAttempts = properties.reconcile.maxAttempts))
            done
        }
        return settleRefunds(ended)
    }

    /** 끝난 구독의 환불을 (다시) 보낸다 - 이미 보낸 줄은 건너뛴다. 작업과 [cancelNow] 가 같은 길이다. */
    fun settleRefunds(subscription: PaymentSubscription): CancelNowResult {
        val at = checkNotNull(subscription.endedAt) { "a mid-term canceled subscription has an end time" }
        val payments = periodPayments(subscription)
        val quote = quote(subscription, at, payments)
        var left = quote.refund
        val results = payments.sortedByDescending { it.order.paidAt }.mapNotNull { payment ->
            val amount = minOf(left, payment.net)
            if (amount <= 0) return@mapNotNull null
            left -= amount
            CancelRefundResult(payment.order.id, amount, send(subscription, payment.order.id, amount))
        }
        return CancelNowResult(store.findSubscription(subscription.id) ?: subscription, quote, results)
    }

    private fun send(subscription: PaymentSubscription, orderId: String, amount: Long): CancelRefundStatus {
        val status = try {
            when (refunds.refundForSubscriptionCancel(subscription.accountId, orderId, amount, seedOf(subscription, orderId)).refund.status) {
                PaymentRefundStatus.SUCCEEDED -> CancelRefundStatus.SUCCEEDED
                PaymentRefundStatus.FAILED -> CancelRefundStatus.FAILED
                else -> CancelRefundStatus.PENDING
            }
        } catch (e: PaymentRefundDeclinedException) {
            CancelRefundStatus.FAILED
        } catch (e: Exception) {
            log.warn("a mid-term cancel refund was not sent subscription={} order={} exception={}", subscription.id, orderId, e.javaClass.simpleName)
            // 다시 해도 소용없는 거절(금액 초과 · 모드 불일치 · 환불할 수 없는 상태 · 없는 주문)은 재시도하지 않고 바로 사람에게 - 나머지(결제사 확인 실패 · 열린 환불)는 작업이 다시 보낸다
            if (e is PaymentRefundAmountExceededException || e is PaymentModeMismatchException || e is PaymentRefundNotAllowedException || e is PaymentOrderNotFoundException) CancelRefundStatus.FAILED else CancelRefundStatus.NOT_SENT
        }
        if (status == CancelRefundStatus.FAILED) flagForReview(orderId)
        return status
    }

    private fun seedOf(subscription: PaymentSubscription, orderId: String) = "subcancel:${subscription.id}:$orderId"

    /** 주문에 「확인 필요」를 찍는다(운영자 확인 필요 목록에 뜬다) - 이미 표시가 있으면 그대로. */
    private fun flagForReview(orderId: String) {
        store.inTransaction { tx ->
            val locked = tx.lockOrder(orderId) ?: return@inTransaction
            convergence.flagReview(tx, locked, PaymentReviewReason.SUBSCRIPTION_CANCEL_REFUND_UNRESOLVED, ConvergeContext(locked.providerMode, ConvergeSource.RECONCILE))
        }
    }

    /**
     * 환불 작업이 죽었다 - 아직 환불 줄이 없거나 결제사가 거절한 주문을 모두 「확인 필요」로 올린다(결제사는 부르지 않는다). 두 번 불려도 한 번.
     */
    fun flagUnrefunded(subscription: PaymentSubscription) {
        val payments = periodPayments(subscription)
        var left = quote(subscription, subscription.endedAt ?: return, payments).refund
        payments.sortedByDescending { it.order.paidAt }.forEach { payment ->
            val amount = minOf(left, payment.net)
            if (amount <= 0) return@forEach
            left -= amount
            val line = store.findRefundByDedupeKey(PaymentOrderIds.dedupeKey(subscription.accountId, IdempotencyKey(seedOf(subscription, payment.order.id))))
            if (line == null || line.status == PaymentRefundStatus.FAILED) flagForReview(payment.order.id)
        }
    }

    /** 이번 기간에 돈을 낸 주문과 각각의 **순액**(결제사 기준 총액 - 이 해지가 아닌 환불). 이 해지가 이미 돌려준 몫은 빼지 않는다 - 다시 계산해도 같은 값이 나오게. */
    private class PeriodPayment(val order: PaymentOrder, val net: Long)

    private fun periodPayments(subscription: PaymentSubscription): List<PeriodPayment> {
        val start = subscription.currentPeriodStart ?: return emptyList()
        val end = subscription.currentPeriodEnd ?: return emptyList()
        return store.listSubscriptionOrders(subscription.id, ORDERS_LOOKED_AT)
            .filter { it.status.isPaidFamily && it.paidAt != null && !it.paidAt.isBefore(start) && it.paidAt.isBefore(end) }
            .map { order ->
                val ours = store.listRefunds(order.id).filter { it.reasonCode == PaymentRefundReason.SUBSCRIPTION_CANCEL.name && it.status == PaymentRefundStatus.SUCCEEDED }.sumOf { it.amount.amount }
                PeriodPayment(order, RefundLines.baseOf(order) - (order.refundedAmount - ours))
            }
            .filter { it.net > 0 }
    }

    private fun quote(subscription: PaymentSubscription, at: java.time.Instant, payments: List<PeriodPayment> = periodPayments(subscription)): CancelQuote {
        if (subscription.isProviderManaged) return CancelQuote.CLOSED // 결제사가 갱신하는 구독은 결제사의 정책을 따른다(기간 끝 해지만)
        val start = subscription.currentPeriodStart ?: return CancelQuote.CLOSED
        val covers = payments.map { CancelPayment(it.net, if (it.order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE) it.order.paidAt ?: start else start) }
        val context = CancelContext(subscription, payments.sumOf { it.net }, at, monthlyPrice(subscription), usage.used(subscription, start, at), properties.subscription.prorationZone, covers)
        return policies.forCountry(subscription.country).quote(context)
    }

    /** 월보다 긴 주기의 구독이 쓴 몫을 셀 때 쓰는 **같은 등급의 월 가격** - 없으면 null(일할로 센다). */
    private fun monthlyPrice(subscription: PaymentSubscription): Long? {
        val months = runCatching { SubscriptionPeriod.parse(subscription.period).calendarMonths }.getOrNull()
        if (months == null || months <= 1) return null
        val current = plans.find(subscription.planCode) ?: return null
        return plans.list().firstOrNull {
            it.level == current.level && it.amount.currency == subscription.amount.currency && runCatching { SubscriptionPeriod.parse(it.period).calendarMonths }.getOrNull() == 1
        }?.amount?.amount
    }

    private fun owned(subscription: PaymentSubscription?, accountId: String): PaymentSubscription? = subscription?.takeIf { it.accountId == accountId }

    private companion object {
        const val ORDERS_LOOKED_AT = 20
    }
}

/** `payment-subscription-cancel-refund` - 중도 해지로 끝난 구독의 환불 중 보내지 못한 것을 다시 보낸다. 다 보냈으면 끝, 못 보낸 것이 남으면 다시(큐의 재시도). */
class SubscriptionCancelRefundTaskHandler(private val store: PaymentLedgerStore, private val cancellations: SubscriptionCancellations) : PaymentTaskHandler {
    override val type: String = PaymentTaskTypes.SUBSCRIPTION_CANCEL_REFUND

    override fun handle(run: PaymentTaskRun) {
        store.requireNoOpenTransaction("The mid-term cancel refund task")
        val subscription = store.findSubscription(run.subjectId)?.takeIf { it.status == SubscriptionStatus.ENDED && it.endReason == SubscriptionEndReason.USER_CANCELED_NOW } ?: return
        val result = cancellations.settleRefunds(subscription)
        if (result.refunds.any { it.status == CancelRefundStatus.NOT_SENT }) throw PaymentTaskRetryException("A refund of the canceled subscription ${subscription.id} could not be sent yet")
    }

    /** 시도를 다 써도 못 보낸 환불은 조용히 사라지지 않게 주문을 「확인 필요」로 올린다. */
    override fun onDead(run: PaymentTaskRun) {
        store.findSubscription(run.subjectId)?.takeIf { it.status == SubscriptionStatus.ENDED && it.endReason == SubscriptionEndReason.USER_CANCELED_NOW }?.let(cancellations::flagUnrefunded)
    }
}
