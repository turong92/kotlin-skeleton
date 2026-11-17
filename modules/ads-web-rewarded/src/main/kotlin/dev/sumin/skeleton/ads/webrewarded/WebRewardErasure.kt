package dev.sumin.skeleton.ads.webrewarded

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/** 계정이 지워질 때 그 계정의 광고 세션 줄과 계정 카운터를 지운다 (멱등) — IP 카운터는 계정이 아니라 남는다 */
class WebRewardErasure(private val store: WebRewardStore) : AccountErasureListener {
    override val name: String = "ads-web-rewarded"

    override fun erase(request: ErasureRequest) = store.inTransaction { store.eraseAccount(request.accountId) }
}
