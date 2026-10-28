package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardGrants
import java.security.SecureRandom
import java.time.Duration

/** 앱이 「의미 있는 행동」(예: 첫 결제)을 알릴 때 부르는 자리 - `qualifyOn: SIGNAL` 인 초대 이벤트의 초대자 보상이 이때 정해진다. 몇 번 불러도 한 번만 먹는다. */
fun interface Referrals {
    fun qualify(inviteeId: String)
}

data class ReferralSummary(val code: String, val invited: Int, val rewarded: Int)

/**
 * 친구초대. 코드는 계정당 하나(처음 볼 때 만든다). claim 한 트랜잭션에서 판정한다: 진행 중인 초대 이벤트 · 없는 코드 · 자기 코드 · 이미 초대받음 · 맞초대 ·
 * 새 계정(가입 후 `claimWithinDays` 안, [AccountFacts] 가 없으면 이 판정만 건너뛴다) — 통과하면 관계 줄 + 초대받은 사람 보상 지급 건.
 * 초대자 보상은 `CLAIM` 이면 같은 트랜잭션, `SIGNAL` 이면 [qualify] 때. 초대자 상한([ReferralConfig.inviterCap])을 넘으면 관계만 남고 보상은 없다(`CAPPED`).
 */
class ReferralService(
    private val store: CampaignStore,
    private val campaigns: Campaigns,
    private val grants: RewardGrants,
    private val facts: AccountFacts?,
    private val time: TimeProvider,
    private val random: SecureRandom = SecureRandom(),
) : Referrals {
    fun me(accountId: String): ReferralSummary {
        val code = store.inTransaction { store.referralCode(accountId, ::newCode) }
        return ReferralSummary(code, store.countReferrals(accountId, null, null).toInt(), store.countReferrals(accountId, null, ReferralStatus.QUALIFIED).toInt())
    }

    fun claim(inviteeId: String, rawCode: String) {
        store.inTransaction {
            val campaign = campaigns.active().firstOrNull { it.kind == ReferralKind.KIND } ?: throw CampaignException(CampaignErrorCode.NOT_ACTIVE, "no referral campaign is running")
            val config = ReferralConfig.parse(campaign.config)
            val now = time.now()
            val inviterId = store.accountOfCode(rawCode.trim().uppercase()) ?: reject(ReferralRejection.UNKNOWN_CODE)
            if (inviterId == inviteeId) reject(ReferralRejection.OWN_CODE)
            if (store.referralOfInvitee(inviteeId) != null) reject(ReferralRejection.ALREADY_INVITED)
            if (store.referralOfInvitee(inviterId)?.inviterId == inviteeId) reject(ReferralRejection.MUTUAL)
            if (facts != null) {
                val createdAt = facts.createdAt(inviteeId)
                if (createdAt == null || createdAt.plus(Duration.ofDays(config.claimWithinDays.toLong())).isBefore(now)) reject(ReferralRejection.NOT_NEW_ACCOUNT)
            }
            val capped = config.qualifyOn == QualifyOn.CLAIM && config.inviterCap != null &&
                store.countReferrals(inviterId, campaign.id, ReferralStatus.QUALIFIED) >= config.inviterCap
            val status = when {
                capped -> ReferralStatus.CAPPED
                config.qualifyOn == QualifyOn.CLAIM -> ReferralStatus.QUALIFIED
                else -> ReferralStatus.CLAIMED
            }
            val referral = Referral(inviteeId, inviterId, campaign.id, status, now, if (status == ReferralStatus.QUALIFIED) now else null)
            if (!store.insertReferral(referral)) reject(ReferralRejection.ALREADY_INVITED)   // 같은 사람이 동시에 두 번 - 평생 한 번
            if (status != ReferralStatus.CAPPED) {
                record(inviteeId, config.invitee, campaign.id, inviteeId, "invitee")
                if (status == ReferralStatus.QUALIFIED) record(inviterId, config.inviter, campaign.id, inviteeId, "inviter")
            }
        }
    }

    override fun qualify(inviteeId: String) {
        store.inTransaction {
            val referral = store.referralOfInvitee(inviteeId) ?: return@inTransaction
            if (referral.status != ReferralStatus.CLAIMED) return@inTransaction
            val campaign = store.find(referral.campaignId) ?: return@inTransaction
            val config = ReferralConfig.parse(campaign.config)
            val capped = config.inviterCap != null && store.countReferrals(referral.inviterId, campaign.id, ReferralStatus.QUALIFIED) >= config.inviterCap
            val to = if (capped) ReferralStatus.CAPPED else ReferralStatus.QUALIFIED
            if (store.settleReferral(inviteeId, to, time.now()) && to == ReferralStatus.QUALIFIED) {
                record(referral.inviterId, config.inviter, campaign.id, inviteeId, "inviter")
            }
        }
    }

    private fun record(accountId: String, rewards: List<RewardSpec>, campaignId: String, inviteeId: String, side: String) {
        rewards.forEachIndexed { i, spec -> grants.record(accountId, spec.type, spec.params, SOURCE_TYPE, "$campaignId:$inviteeId:$side:$i") }
    }

    private fun reject(reason: ReferralRejection): Nothing =
        throw CampaignException(CampaignErrorCode.REFERRAL_INVALID, reason.name, mapOf("reason" to reason.name))

    /** 헷갈리는 글자(0 O 1 I)를 뺀 8자 */
    private fun newCode(): String = String(CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    companion object {
        const val SOURCE_TYPE = "referral"
        const val CODE_LENGTH = 8
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
