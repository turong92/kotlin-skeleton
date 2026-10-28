package dev.sumin.skeleton.reward.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.reward.RewardGrantStore

/** 계정이 지워질 때 그 계정의 지급 건을 지운다 (멱등) */
class RewardAccountErasureListener(private val store: RewardGrantStore) : AccountErasureListener {
    override val name: String = "reward"

    override fun erase(request: ErasureRequest) {
        store.deleteByAccount(request.accountId)
    }
}
