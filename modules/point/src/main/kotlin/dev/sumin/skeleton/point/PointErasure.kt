package dev.sumin.skeleton.point

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/** 계정이 지워질 때 그 계정의 잔액과 모든 원장 줄을 지운다 (멱등) — `account` 의 `AccountPurgeService` 가 모아 부른다 */
class PointErasure(private val store: PointStore) : AccountErasureListener {
    override val name: String = "point"

    override fun erase(request: ErasureRequest) = store.eraseAccount(request.accountId)
}
