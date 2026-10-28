package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountMath
import dev.sumin.skeleton.common.discount.DiscountOffer
import dev.sumin.skeleton.common.discount.DiscountStage
import java.time.Instant

/** 「내 구독」에 보이는 할인 - [nextAmount] 는 다음 과금액, [remainingPeriods] 는 남은 할인 회차(영원이면 null). */
data class SubscriptionDiscountView(
    val label: String,
    val stages: List<DiscountStage>,
    val periodsUsed: Int,
    val nextAmount: PaymentAmount,
    val remainingPeriods: Int?,
)

/**
 * 구독의 할인 일정(스펙 §5) - 쿠폰의 단계 목록을 시작 때 **복사**해 두고, 과금마다 `periods_used` 위치의 단계로 금액을 깎는다.
 *  - 회차 = [SubscriptionDiscount.periodsUsed]. **할인이 붙은 구독 주문(첫 결제 · 갱신)이 `PAID` 될 때만** [onPaid] 가 1 올린다(지급 고리 안, 구독 락 안) -
 *    같은 기간의 재시도는 같은 금액이고 실패한 시도는 회차를 쓰지 않는다. 단계를 다 쓰면 `ENDED` 이고 그 뒤는 정가.
 *  - 플랜 변경의 차액 결제(`SUBSCRIPTION_CHANGE`)에는 할인도 회차도 없다 - 차액은 일할 계산이라 정률을 또 깎으면 두 번 깎인다. 변경 뒤의 갱신은 새 플랜 금액에 남은 단계를 이어서 적용한다
 *    (금액이 구독 줄의 [PaymentSubscription.amount] 에서 나오므로 정액은 새 금액을 넘지 않게 [DiscountMath] 가 자른다).
 *  - 구독이 끝나면 [end] 가 할인도 끝낸다(다시 구독하면 새 줄 - 쿠폰은 되살아나지 않는다). 해지 예약 중에는 남은 단계가 보존된다.
 */
class SubscriptionDiscounts(
    private val store: PaymentLedgerStore,
    private val now: () -> Instant,
) {
    /** 시작 때 쿠폰의 [offer] 를 단계째 복사한다. 호출한 쪽이 구독 줄을 넣은(또는 잠근) 트랜잭션 안이다. */
    fun attach(tx: PaymentLedgerTx, subscription: PaymentSubscription, offer: DiscountOffer, kind: String) {
        val at = now()
        tx.insertSubscriptionDiscount(
            SubscriptionDiscount(subscription.id, kind, offer.sourceRef, offer.label.take(LABEL_MAX), offer.stages, periodsUsed = 0, status = SubscriptionDiscountStatus.ACTIVE, createdAt = at, updatedAt = at),
        )
    }

    /** 이번 과금액 - 할인이 없거나 단계를 다 썼으면 [PaymentSubscription.amount] 그대로. */
    fun amountFor(subscription: PaymentSubscription, discount: SubscriptionDiscount?): PaymentAmount {
        val stage = currentStage(discount) ?: return subscription.amount
        val off = DiscountMath.discount(subscription.amount.amount, stage, scaleOf(subscription.amount.normalizedCurrency))
        return PaymentAmount(subscription.amount.amount - off, subscription.amount.currency)
    }

    /** 구독 락을 잡은 트랜잭션 안에서 읽는 버전. */
    fun amountFor(tx: PaymentLedgerTx, subscription: PaymentSubscription): PaymentAmount = amountFor(subscription, tx.subscriptionDiscount(subscription.id))

    /** 지급 고리 - 할인이 붙은 구독 주문이 `PAID` 가 됐다. 회차를 하나 쓰고, 단계를 다 썼으면 할인을 끝낸다. */
    fun onPaid(tx: PaymentLedgerTx, subscription: PaymentSubscription, order: PaymentOrder) {
        if (order.kind == PaymentOrderKind.SUBSCRIPTION_CHANGE) return
        val discount = tx.subscriptionDiscount(subscription.id)?.takeIf { it.status == SubscriptionDiscountStatus.ACTIVE } ?: return
        val used = discount.periodsUsed + 1
        val finished = DiscountMath.stageAt(discount.stages, used) == null
        tx.updateSubscriptionDiscount(discount.copy(periodsUsed = used, status = if (finished) SubscriptionDiscountStatus.ENDED else SubscriptionDiscountStatus.ACTIVE, updatedAt = now()))
    }

    /** 구독이 끝났다 - 할인도 끝난다. */
    fun end(tx: PaymentLedgerTx, subscriptionId: String) {
        val discount = tx.subscriptionDiscount(subscriptionId)?.takeIf { it.status == SubscriptionDiscountStatus.ACTIVE } ?: return
        tx.updateSubscriptionDiscount(discount.copy(status = SubscriptionDiscountStatus.ENDED, updatedAt = now()))
    }

    /** 「내 구독」용 - 끝난 할인은 보이지 않는다(null). */
    fun view(subscription: PaymentSubscription): SubscriptionDiscountView? {
        val discount = store.findSubscriptionDiscount(subscription.id)?.takeIf { it.status == SubscriptionDiscountStatus.ACTIVE } ?: return null
        val total = discount.stages.sumOf { it.periods ?: return@sumOf 0 }.takeIf { discount.stages.last().periods != null }
        return SubscriptionDiscountView(discount.label, discount.stages, discount.periodsUsed, amountFor(subscription, discount), total?.let { (it - discount.periodsUsed).coerceAtLeast(0) })
    }

    private fun currentStage(discount: SubscriptionDiscount?): DiscountStage? =
        discount?.takeIf { it.status == SubscriptionDiscountStatus.ACTIVE }?.let { DiscountMath.stageAt(it.stages, it.periodsUsed) }

    private fun scaleOf(currency: String): Int =
        try {
            java.util.Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)
        } catch (_: IllegalArgumentException) {
            2
        }

    private companion object {
        const val LABEL_MAX = 100
    }
}
