package dev.sumin.skeleton.campaign.erasure

import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/** 계정이 지워질 때 출석 · 초대 코드 · 초대 관계를 지운다 (멱등) */
class CampaignAccountErasureListener(private val store: CampaignStore) : AccountErasureListener {
    override val name: String = "campaign"

    override fun erase(request: ErasureRequest) = store.deleteByAccount(request.accountId)
}
