package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.reward.RewardIssuer

/**
 * 이벤트 종류 - 설정 모양을 알고 저장 전에 검증한다. 지금 둘(출석 · 초대), 다음 판 기념일 · 공유 · 달성.
 * 설정이 틀렸거나 모르는 보상 종류면 `400 CAMPAIGN.CONFIG_INVALID`.
 */
interface CampaignKind {
    val kind: String

    fun validate(config: Map<String, Any?>, issuers: Map<String, RewardIssuer>)
}

class AttendanceKind : CampaignKind {
    override val kind = KIND

    override fun validate(config: Map<String, Any?>, issuers: Map<String, RewardIssuer>) {
        val parsed = AttendanceConfig.parse(config)
        ConfigReader.checkRewards(parsed.daily + parsed.milestones.flatMap { it.rewards }, issuers)
    }

    companion object {
        const val KIND = "attendance"
    }
}

class ReferralKind : CampaignKind {
    override val kind = KIND

    override fun validate(config: Map<String, Any?>, issuers: Map<String, RewardIssuer>) {
        val parsed = ReferralConfig.parse(config)
        ConfigReader.checkRewards(parsed.invitee + parsed.inviter, issuers)
    }

    companion object {
        const val KIND = "referral"
    }
}
