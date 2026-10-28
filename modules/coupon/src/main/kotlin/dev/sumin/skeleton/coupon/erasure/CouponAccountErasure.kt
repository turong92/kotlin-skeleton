package dev.sumin.skeleton.coupon.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.coupon.CouponStore

/**
 * 계정이 지워질 때 그 계정의 쿠폰 줄에서 계정 id 를 비식별값(`tombstone`)으로 바꾸고 공용 코드 등록 줄을 지운다 (멱등) - 결제 장부의 `TOMBSTONE` 과 같은 방식.
 * 줄 자체는 남아 과거의 발급 · 사용 현황과 대시보드 수가 줄지 않는다. 쓰지 않은 쿠폰은 `REVOKED` 가 된다. 결제 장부의 할인 내역은 장부라 남는다.
 */
class CouponAccountErasureListener(private val store: CouponStore, private val now: () -> java.time.Instant = { java.time.Instant.now() }) : AccountErasureListener {
    override val name: String = "coupon"

    override fun erase(request: ErasureRequest) {
        store.anonymizeAccount(request.accountId, request.tombstone, now())
    }
}
