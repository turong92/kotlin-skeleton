package dev.sumin.skeleton.payment

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountConfirmOutcome
import dev.sumin.skeleton.common.discount.DiscountMath
import dev.sumin.skeleton.common.discount.DiscountOffer
import dev.sumin.skeleton.common.discount.DiscountPurpose
import dev.sumin.skeleton.common.discount.DiscountRejectedException
import dev.sumin.skeleton.common.discount.DiscountRejection
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountStageType
import dev.sumin.skeleton.common.discount.DiscountTarget
import org.slf4j.LoggerFactory

/** 할인 수단이 내놓은 한 줄 - 장부의 [OrderDiscount] 가 되기 전 모양. [sourceRef] 는 확정 · 복원이 쓰는 원천 식별(쿠폰 id). */
data class DiscountDraft(val kind: String, val sourceRef: String, val label: String, val amount: Long)

/** 구독 쿠폰의 제안 - [firstDiscount] 는 첫 과금에서 깎이는 금액이다. */
data class SubscriptionOffer(val kind: String, val offer: DiscountOffer, val firstDiscount: Long)

/** 할인 뒤 금액의 미리보기(또는 예약 결과) - [amount] 는 최종가, [originalAmount] 는 정가. [amount] 가 0 이면 결제사 없이 끝나는 주문이다. */
data class DiscountQuote(val originalAmount: Long, val amount: Long, val currency: String, val lines: List<DiscountDraft>) {
    val discountAmount: Long get() = originalAmount - amount
}

/**
 * 결제 쪽 할인 수단 모으기 - 수단([DiscountSource] 빈들)을 `order` 순으로 불러 금액을 깎고, 주문의 한살이에 맞춰 예약 → 확정 / 해제 / 복원을 건다(스펙 §4.5).
 * 결제 면제는 할인이 아니라 [PaymentOrders] 안의 따로 된 길이다. 수단 빈이 하나도 없으면 아무것도 하지 않는다(스켈레톤 중립 - 이 모듈들을 안 붙인 앱의 결제는 그대로).
 *
 * 예약 · 확정 · 해제 · 복원은 **주문 줄을 잠근 트랜잭션 안에서** 부른다 - 수단이 같은 DB 를 쓰면 같은 커밋에 묶인다(지급 리스너와 같은 전제).
 * 수단은 같은 호출을 다시 받아도 같은 결과여야 한다(멱등).
 */
class PaymentDiscounts(
    private val sources: () -> List<DiscountSource>,
    private val store: PaymentLedgerStore,
    private val now: () -> java.time.Instant,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 할인 수단이 하나라도 붙어 있는가 - 없으면 주문 만들기 · 끝 상태 전이가 이 클래스를 거쳐도 아무 일도 하지 않는다. */
    val active: Boolean get() = sources().isNotEmpty()

    /** [accountId] 가 [amount] 짜리 [itemType]/[itemRef] 를 한 번 사는 일의 할인 대상. 「첫 결제만」 판정은 장부가 안다 - 결제사로 돈을 낸 적이 없으면 첫 결제. */
    fun targetFor(accountId: String, itemType: String, itemRef: String, amount: PaymentAmount, productCode: String? = null): DiscountTarget =
        DiscountTarget(
            accountId = accountId,
            itemType = itemType,
            itemRef = itemRef,
            planCode = null,
            amount = amount.amount,
            currency = amount.normalizedCurrency,
            purpose = DiscountPurpose.ONE_TIME,
            firstPurchase = !store.hasPaidOrder(accountId),
            productCode = productCode,
        )

    /** 구독을 시작하는 일의 할인 대상 - 플랜 코드가 쿠폰 범위의 열쇠다. 「첫 결제만」은 단건과 같은 판정. */
    fun subscriptionTarget(accountId: String, plan: SubscriptionPlan): DiscountTarget =
        DiscountTarget(accountId, SubscriptionLifecycle.ITEM_TYPE, plan.code, plan.code, plan.amount.amount, plan.amount.normalizedCurrency, DiscountPurpose.SUBSCRIPTION, firstPurchase = !store.hasPaidOrder(accountId), productCode = plan.productCode)

    /**
     * 구독 쿠폰 - 수단이 낸 제안(단계 목록)과 첫 과금에 걸리는 할인. [holderRef] 가 null 이면 미리보기(아무것도 안 쥔다, 시작 때), 있으면 그 주문이 쥔다(첫 결제 때).
     * 첫 단계가 깎는 것이 없으면 못 쓰는 쿠폰이다.
     */
    fun subscriptionOffer(claims: List<DiscountClaim>, target: DiscountTarget, holderRef: String? = null): SubscriptionOffer {
        if (claims.size != MAX_CLAIMS) throw PaymentDiscountNotApplicableException("Only $MAX_CLAIMS discount can be used on a subscription for now.")
        val claim = claims.single()
        val source = sources().firstOrNull { it.kind == claim.kind } ?: throw PaymentDiscountNotApplicableException("This discount is not offered here.")
        val offer = rejected { if (holderRef == null) source.quote(claim, target) else source.reserve(claim, target, holderRef) }
        val stage = DiscountMath.stageAt(offer.stages, 0) ?: throw PaymentDiscountNotApplicableException()
        val off = DiscountMath.discount(target.amount, stage, scaleOf(target.currency))
        if (off <= 0) throw PaymentDiscountNotApplicableException("The discount takes nothing off this purchase.")
        return SubscriptionOffer(claim.kind, offer, off)
    }

    /** 부수 효과 없는 미리보기 - 아무것도 잡지 않는다. 못 쓰는 할인이면 [PaymentDiscountNotApplicableException] · [PaymentDiscountUnavailableException]. */
    fun quote(claims: List<DiscountClaim>, target: DiscountTarget, units: Int = 1): DiscountQuote = apply(claims, target, units) { source, claim, at -> source.quote(claim, at) }

    /** 쥔다 - [holderRef](주문 id)가 쓰려고 잡는다. [quote] 와 같은 계산이다. */
    fun reserve(claims: List<DiscountClaim>, target: DiscountTarget, holderRef: String, units: Int = 1): DiscountQuote =
        apply(claims, target, units) { source, claim, at -> source.reserve(claim, at, holderRef) }

    /**
     * 결제가 끝났다 - 쥔 것(또는 늦은 `PAID` 라서 이미 풀린 것)을 쓴 것으로 굳힌다. 그사이 다른 주문이 쥐어서 못 내주면 true(충돌) - 호출한 쪽이 주문에 검토 표시를 단다.
     * 이미 굳힌 줄은 건드리지 않는다(지급 재시도에 한 번).
     */
    fun confirm(tx: PaymentLedgerTx, orderId: String): Boolean {
        if (!active) return false
        var conflict = false
        pending(tx, orderId, OrderDiscountStatus.RESERVED, OrderDiscountStatus.RELEASED).forEach { (kind, lines) ->
            val source = sourceOf(kind) ?: return@forEach
            when (source.confirm(orderId)) {
                DiscountConfirmOutcome.CONFIRMED, DiscountConfirmOutcome.ALREADY -> mark(tx, lines, OrderDiscountStatus.CONFIRMED)
                DiscountConfirmOutcome.CONFLICT -> {
                    conflict = true
                    mark(tx, lines.filter { it.status == OrderDiscountStatus.RESERVED }, OrderDiscountStatus.RELEASED)
                }
            }
        }
        return conflict
    }

    /** 결제가 안 됐다(실패 · 만료 · 그만둠) - 이 주문이 쥔 것만 푼다. */
    fun release(tx: PaymentLedgerTx, orderId: String) {
        if (!active) return
        pending(tx, orderId, OrderDiscountStatus.RESERVED).forEach { (kind, lines) ->
            val source = sourceOf(kind) ?: return@forEach
            source.release(orderId)
            mark(tx, lines, OrderDiscountStatus.RELEASED)
        }
    }

    /**
     * 전액 환불 - 쓴 것은 돌려준다. **지급 전에** 전액 환불된 주문은 아직 쓴 것이 아니라 쥐고만 있으니 [DiscountSource.release] 로 푼다(쓴 적 없는 것을 되돌리지 않는다).
     * 부분 환불은 이 함수를 부르지 않는다 - 할인액 안분 없음(환불 상한이 이미 최종가다).
     */
    fun restore(tx: PaymentLedgerTx, orderId: String) {
        if (!active) return
        pending(tx, orderId, OrderDiscountStatus.CONFIRMED, OrderDiscountStatus.RESERVED).forEach { (kind, lines) ->
            val source = sourceOf(kind) ?: return@forEach
            val used = lines.filter { it.status == OrderDiscountStatus.CONFIRMED }
            val held = lines.filter { it.status == OrderDiscountStatus.RESERVED }
            if (used.isNotEmpty()) {
                source.restore(orderId)
                mark(tx, used, OrderDiscountStatus.RESTORED)
            }
            if (held.isNotEmpty()) {
                source.release(orderId)
                mark(tx, held, OrderDiscountStatus.RELEASED)
            }
        }
    }

    private fun pending(tx: PaymentLedgerTx, orderId: String, vararg statuses: OrderDiscountStatus): Map<String, List<OrderDiscount>> =
        tx.listOrderDiscounts(orderId).filter { it.status in statuses }.groupBy { it.kind }

    private fun mark(tx: PaymentLedgerTx, lines: List<OrderDiscount>, status: OrderDiscountStatus) {
        val at = now()
        lines.forEach { tx.updateOrderDiscountStatus(it.orderId, it.seq, status, at) }
    }

    private fun sourceOf(kind: String): DiscountSource? =
        sources().firstOrNull { it.kind == kind }.also { if (it == null) log.warn("a discount line names a discount source that is not wired kind={}", kind) }

    /**
     * 쿠폰은 한 장 = 한 개다(국룰): [units] 개를 한꺼번에 사도 **무료 · 정률** 할인은 단가 한 개분에만 걸린다 - 무료 · 100% 쿠폰 한 장으로 수량 1000 개가 0원이 되지 않는다.
     * 정액은 합계에서 그대로 깎는다(합계를 넘지 않게). 수단에는 늘 **주문 합계**를 대상 금액으로 넘긴다 - 최소 금액 · 범위 판정은 합계 기준이다
     * (「5만원 이상 5천원 할인」을 1만원 × 5 주문에 쓸 수 있다).
     */
    private fun apply(claims: List<DiscountClaim>, target: DiscountTarget, units: Int, call: (DiscountSource, DiscountClaim, DiscountTarget) -> DiscountOffer): DiscountQuote {
        require(units >= 1) { "units is at least 1" }
        if (claims.isEmpty()) return DiscountQuote(target.amount, target.amount, target.currency, emptyList())
        if (claims.size > MAX_CLAIMS) throw PaymentDiscountNotApplicableException("Only $MAX_CLAIMS discount can be used on an order for now.")
        val wired = sources().sortedBy { it.order }
        val ordered = claims.map { claim -> claim to (wired.firstOrNull { it.kind == claim.kind } ?: throw PaymentDiscountNotApplicableException("This discount is not offered here.")) }
            .sortedBy { it.second.order }
        val scale = scaleOf(target.currency)
        var running = target.amount
        val lines = ArrayList<DiscountDraft>()
        for ((claim, source) in ordered) {
            val offer = rejected { call(source, claim, target.copy(amount = running)) }
            val stage = DiscountMath.stageAt(offer.stages, 0) ?: throw PaymentDiscountNotApplicableException()
            val base = if (stage.type == DiscountStageType.FIXED) running else running / units   // 무료 · 정률은 단가 한 개분, 정액은 합계에서
            val off = minOf(DiscountMath.discount(base, stage, scale), running)
            if (off <= 0) throw PaymentDiscountNotApplicableException("The discount takes nothing off this purchase.")
            running -= off
            lines += DiscountDraft(source.kind, offer.sourceRef, offer.label.take(LABEL_MAX), off)
        }
        return DiscountQuote(target.amount, running, target.currency, lines)
    }

    private inline fun <T> rejected(block: () -> T): T =
        try {
            block()
        } catch (e: DiscountRejectedException) {
            throw when (e.reason) {
                DiscountRejection.NOT_APPLICABLE -> PaymentDiscountNotApplicableException()
                DiscountRejection.UNAVAILABLE -> PaymentDiscountUnavailableException()
            }
        }

    private fun scaleOf(currency: String): Int =
        try {
            java.util.Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)
        } catch (_: IllegalArgumentException) {
            2
        }

    companion object {
        /** 첫 판은 한 주문에 할인 하나(쿠폰 한 장). */
        const val MAX_CLAIMS = 1
        const val LABEL_MAX = 100
    }
}
