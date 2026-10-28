package dev.sumin.skeleton.coupon

import dev.sumin.skeleton.common.time.TimeProvider

/** 계정의 쿠폰함 읽기와 운영자 회수 */
class CouponWallet(
    private val store: CouponStore,
    private val time: TimeProvider,
) {
    /** 새것부터. `AVAILABLE` 탭은 쓸 수 있는 것(주문이 쥔 것 포함), `PAST` 탭은 쓴 · 끝난 · 회수된 것 */
    fun list(accountId: String, tab: CouponTab): List<CouponView> = all(accountId).filter {
        val live = it.status == CouponStatus.AVAILABLE || it.status == CouponStatus.RESERVED
        if (tab == CouponTab.AVAILABLE) live else !live
    }

    fun all(accountId: String): List<CouponView> {
        val now = time.now()
        val coupons = store.listByAccount(accountId, MAX_LIST)
        val definitions = store.findDefinitions(coupons.map { it.definitionId }.toSet())
        return coupons.mapNotNull { coupon -> definitions[coupon.definitionId]?.let { CouponView(coupon, it, CouponExpiry.statusAt(coupon, now)) } }
    }

    /** 장 하나를 정의와 함께 읽는다 */
    fun view(coupon: Coupon): CouponView =
        CouponView(coupon, store.findDefinition(coupon.definitionId) ?: error("coupon definition ${coupon.definitionId} vanished"), CouponExpiry.statusAt(coupon, time.now()))

    /** `AVAILABLE` 인 쿠폰만 회수한다. 없으면 404, 그 밖의 상태는 409 */
    fun revoke(couponId: String, inTransaction: (CouponView) -> Unit = {}): CouponView = store.inTransaction {
        val now = time.now()
        if (!store.revoke(couponId, now)) {
            if (store.findCoupon(couponId) == null) throw CouponException(CouponErrorCode.NOT_FOUND, "coupon $couponId not found")
            throw CouponException(CouponErrorCode.NOT_REVOCABLE, "coupon $couponId is not available")
        }
        val coupon = store.findCoupon(couponId)!!
        val definition = store.findDefinition(coupon.definitionId)!!
        CouponView(coupon, definition, coupon.status).also(inTransaction)
    }

    private companion object {
        const val MAX_LIST = 500
    }
}
