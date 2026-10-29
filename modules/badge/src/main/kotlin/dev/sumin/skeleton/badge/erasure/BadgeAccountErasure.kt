package dev.sumin.skeleton.badge.erasure

import dev.sumin.skeleton.badge.BadgeStore
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/** 계정이 지워질 때 그 계정의 배지 보유 줄과 수여 · 회수 내역을 지운다 (멱등) - 배지 정의는 계정과 무관해 그대로다 */
class BadgeAccountErasureListener(private val store: BadgeStore) : AccountErasureListener {
    override val name: String = "badge"

    override fun erase(request: ErasureRequest) {
        store.eraseAccount(request.accountId)
    }
}
