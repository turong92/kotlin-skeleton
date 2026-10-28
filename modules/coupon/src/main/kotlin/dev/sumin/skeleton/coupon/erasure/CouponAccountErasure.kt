package dev.sumin.skeleton.coupon.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.coupon.CouponStore

/** 계정이 지워질 때 그 계정의 쿠폰과 공용 코드 등록 줄을 지운다 (멱등). 결제 장부의 할인 내역은 장부라 남는다 */
class CouponAccountErasureListener(private val store: CouponStore) : AccountErasureListener {
    override val name: String = "coupon"

    override fun erase(request: ErasureRequest) {
        store.deleteByAccount(request.accountId)
    }
}
