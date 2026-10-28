package dev.sumin.skeleton.payment.testing

import dev.sumin.skeleton.common.discount.DiscountClaim
import dev.sumin.skeleton.common.discount.DiscountConfirmOutcome
import dev.sumin.skeleton.common.discount.DiscountOffer
import dev.sumin.skeleton.common.discount.DiscountRejectedException
import dev.sumin.skeleton.common.discount.DiscountRejection
import dev.sumin.skeleton.common.discount.DiscountSource
import dev.sumin.skeleton.common.discount.DiscountStage
import dev.sumin.skeleton.common.discount.DiscountTarget
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 쿠폰 할인 수단 대역 - 쿠폰마다 `AVAILABLE → RESERVED(주문) → USED(주문)` 이고 한 장을 두 주문이 쥘 수 없다.
 * [calls] 가 불린 순서를 남긴다(`reserve:<쿠폰>:<주문>` · `confirm:<주문>` · `release:<주문>` · `restore:<주문>`). 진짜 쿠폰 모듈의 약속(예약은 하나만, 확정은 멱등, 늦은 확정은 다시 잡기)을 같은 모양으로 흉내 낸다.
 */
class FakeCouponSource(override val kind: String = "coupon", override val order: Int = 100) : DiscountSource {
    private class Coupon(val id: String, val label: String, val stages: List<DiscountStage>, val applies: (DiscountTarget) -> Boolean) {
        var state = "AVAILABLE"
        var holder: String? = null
    }

    private val coupons = LinkedHashMap<String, Coupon>()
    val calls = CopyOnWriteArrayList<String>()

    /** 풀린 쿠폰을 누가 쥐었었나 - 만료로 풀린 뒤 늦게 `PAID` 가 된 주문이 아직 비어 있는 쿠폰을 다시 잡는다. */
    private val released = HashMap<String, String>()

    fun add(id: String, stage: DiscountStage, label: String = "Coupon $id", applies: (DiscountTarget) -> Boolean = { true }) = add(id, listOf(stage), label, applies)

    fun add(id: String, stages: List<DiscountStage>, label: String = "Coupon $id", applies: (DiscountTarget) -> Boolean = { true }) {
        coupons[id] = Coupon(id, label, stages, applies)
    }

    fun stateOf(id: String): String = synchronized(this) { coupons.getValue(id).state }

    fun holderOf(id: String): String? = synchronized(this) { coupons.getValue(id).holder }

    /** 시험이 다른 주문이 쥔 것처럼 만든다. */
    fun heldBy(id: String, holder: String) {
        synchronized(this) { coupons.getValue(id).apply { state = "RESERVED"; this.holder = holder } }
    }

    private fun pick(claim: DiscountClaim, target: DiscountTarget): Coupon {
        val coupon = coupons[claim.ref] ?: throw DiscountRejectedException(DiscountRejection.NOT_APPLICABLE)
        if (!coupon.applies(target)) throw DiscountRejectedException(DiscountRejection.NOT_APPLICABLE)
        return coupon
    }

    override fun quote(claim: DiscountClaim, target: DiscountTarget): DiscountOffer = synchronized(this) {
        val coupon = pick(claim, target)
        if (coupon.state != "AVAILABLE") throw DiscountRejectedException(DiscountRejection.UNAVAILABLE)
        DiscountOffer(coupon.id, coupon.label, coupon.stages)
    }

    override fun reserve(claim: DiscountClaim, target: DiscountTarget, holderRef: String): DiscountOffer = synchronized(this) {
        val coupon = pick(claim, target)
        if (coupon.state != "AVAILABLE") throw DiscountRejectedException(DiscountRejection.UNAVAILABLE)
        coupon.state = "RESERVED"
        coupon.holder = holderRef
        calls += "reserve:${coupon.id}:$holderRef"
        DiscountOffer(coupon.id, coupon.label, coupon.stages)
    }

    override fun confirm(holderRef: String): DiscountConfirmOutcome = synchronized(this) {
        calls += "confirm:$holderRef"
        val mine = coupons.values.firstOrNull { it.holder == holderRef }
        if (mine != null) {
            return when (mine.state) {
                "USED" -> DiscountConfirmOutcome.ALREADY
                else -> { mine.state = "USED"; DiscountConfirmOutcome.CONFIRMED }
            }
        }
        val again = released[holderRef]?.let { coupons[it] }
        if (again != null && again.state == "AVAILABLE") {
            again.state = "USED"
            again.holder = holderRef
            return DiscountConfirmOutcome.CONFIRMED
        }
        DiscountConfirmOutcome.CONFLICT
    }

    override fun release(holderRef: String) = synchronized(this) {
        calls += "release:$holderRef"
        coupons.values.filter { it.holder == holderRef && it.state == "RESERVED" }.forEach { released[holderRef] = it.id; it.state = "AVAILABLE"; it.holder = null }
    }

    override fun restore(holderRef: String) = synchronized(this) {
        calls += "restore:$holderRef"
        coupons.values.filter { it.holder == holderRef && it.state == "USED" }.forEach { it.state = "AVAILABLE"; it.holder = null }
    }
}
