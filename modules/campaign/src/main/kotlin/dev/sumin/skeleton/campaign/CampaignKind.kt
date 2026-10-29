package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.reward.RewardIssuer

/**
 * 이벤트 종류 - 설정 모양을 알고 저장 전에 검증한다. 출석 · 초대 · 달성(앱이 수치를 보고한다), 다음 판 기념일 · 공유.
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

/** 업적 - 앱이 [CampaignSignals.report] 로 보고한 수치가 단계 기준값에 닿으면 단계의 보상을 모두 준다. 어떤 보상(배지 · 포인트 · 쿠폰)인지는 모른다 */
class AchievementKind : CampaignKind {
    override val kind = KIND

    override fun validate(config: Map<String, Any?>, issuers: Map<String, RewardIssuer>) {
        val parsed = AchievementConfig.parse(config)
        ConfigReader.checkRewards(parsed.milestones.flatMap { it.rewards }, issuers)
    }

    companion object {
        const val KIND = "achievement"
    }
}
