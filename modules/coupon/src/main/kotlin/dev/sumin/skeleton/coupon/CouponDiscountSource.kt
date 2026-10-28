package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountConfirmOutcome
import dev.sumin.skeleton.common.discount.DiscountOffer
import dev.sumin.skeleton.common.discount.DiscountRejectedException
import dev.sumin.skeleton.common.discount.DiscountRejection
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountTarget
import dev.sumin.skeleton.common.time.TimeProvider

/**
 * 결제 할인 수단 `coupon`(`order` 100 — 쿠폰이 포인트보다 먼저). 쿠폰 id 가 [DiscountClaim.ref] 다.
 * 상태 전이는 주문이 쥔 쿠폰([Coupon.holderRef])을 기준으로 하는 조건부 갱신이라 동시에 와도 한쪽만 이긴다.
 *
 * - 쓸 수 있는 조건은 쿠폰이 이 계정의 것이고 `AVAILABLE` 이며 기한 안일 때. 아니면 [DiscountRejection.UNAVAILABLE].
 * - 범위(목적 · 항목 · 플랜) · 최소 금액 · 통화 · 첫 결제 한정이 안 맞으면 [DiscountRejection.NOT_APPLICABLE].
 * - 이미 발급된 쿠폰은 정의가 멈추거나 보관돼도 쓸 수 있다(발급만 막는다).
 */
class CouponDiscountSource(
    private val store: CouponStore,
    private val time: TimeProvider,
) : DiscountSource {
    override val kind: String = KIND
    override val order: Int = ORDER

    override fun quote(claim: DiscountClaim, target: DiscountTarget): DiscountOffer {
        val (coupon, definition) = load(claim, target)
        return offer(coupon, definition)
    }

    override fun reserve(claim: DiscountClaim, target: DiscountTarget, holderRef: String): DiscountOffer {
        val (coupon, definition) = load(claim, target)
        // 읽은 뒤 사이에 다른 주문이 쥐었으면 조건부 갱신이 비어 돌아온다
        if (!store.reserve(coupon.id, target.accountId, holderRef, time.now())) throw DiscountRejectedException(DiscountRejection.UNAVAILABLE, "coupon is not available")
        return offer(coupon, definition)
    }

    override fun confirm(holderRef: String): DiscountConfirmOutcome {
        if (store.confirm(holderRef, time.now())) return DiscountConfirmOutcome.CONFIRMED
        // 이미 쓴 것이면 그대로, 남이 쥐었거나(주인이 바뀌어 찾지 못한다) 회수됐으면 충돌
        val coupon = store.findByHolder(holderRef)
        return if (coupon?.status == CouponStatus.USED) DiscountConfirmOutcome.ALREADY else DiscountConfirmOutcome.CONFLICT
    }

    override fun release(holderRef: String) {
        store.release(holderRef, time.now())
    }

    override fun restore(holderRef: String) {
        store.restore(holderRef, time.now())
    }

    private fun offer(coupon: Coupon, definition: CouponDefinition) = DiscountOffer(coupon.id, definition.name, definition.stages)

    private fun load(claim: DiscountClaim, target: DiscountTarget): Pair<Coupon, CouponDefinition> {
        val now = time.now()
        val coupon = store.findCoupon(claim.ref)?.takeIf { it.accountId == target.accountId }
            ?: throw DiscountRejectedException(DiscountRejection.UNAVAILABLE, "coupon is not available")
        if (CouponExpiry.statusAt(coupon, now) != CouponStatus.AVAILABLE) throw DiscountRejectedException(DiscountRejection.UNAVAILABLE, "coupon is not available")
        val definition = store.findDefinition(coupon.definitionId)
            ?: throw DiscountRejectedException(DiscountRejection.UNAVAILABLE, "coupon is not available")
        if (!applies(definition, target)) throw DiscountRejectedException(DiscountRejection.NOT_APPLICABLE, "coupon does not apply to this purchase")
        return coupon to definition
    }

    private fun applies(definition: CouponDefinition, target: DiscountTarget): Boolean {
        val scope = definition.scope
        return (scope.purposes.isEmpty() || target.purpose in scope.purposes) &&
            (scope.itemTypes.isEmpty() || target.itemType in scope.itemTypes) &&
            (scope.itemRefs.isEmpty() || target.itemRef in scope.itemRefs) &&
            (scope.planCodes.isEmpty() || (target.planCode != null && target.planCode in scope.planCodes)) &&
            (definition.currency == null || definition.currency == target.currency) &&
            (definition.minAmount == null || target.amount >= definition.minAmount) &&
            (!definition.firstPurchaseOnly || target.firstPurchase)
    }

    companion object {
        const val KIND = "coupon"
        const val ORDER = 100
    }
}
