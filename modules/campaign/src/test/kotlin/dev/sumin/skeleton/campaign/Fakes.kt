package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.reward.RewardGrants
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.TreeMap

/** 메모리 저장소 대역 - 트랜잭션은 없다 */
class FakeCampaignStore : CampaignStore {
    val campaigns = TreeMap<String, Campaign>(reverseOrder())
    val attendance = mutableSetOf<Triple<String, String, LocalDate>>()
    val codes = mutableMapOf<String, String>()
    val referrals = mutableMapOf<String, Referral>()

    override fun <T> inTransaction(block: () -> T): T = block()
    override fun insert(campaign: Campaign) { campaigns[campaign.id] = campaign }
    override fun update(campaign: Campaign): Boolean = campaigns.replace(campaign.id, campaign) != null
    override fun setEnabled(id: String, enabled: Boolean, at: Instant): Boolean {
        val c = campaigns[id] ?: return false
        campaigns[id] = c.copy(enabled = enabled, updatedAt = at)
        return true
    }
    override fun find(id: String) = campaigns[id]
    override fun list() = campaigns.values.toList()
    override fun insertAttendance(campaignId: String, accountId: String, day: LocalDate, at: Instant) = attendance.add(Triple(campaignId, accountId, day))
    override fun attendanceDays(campaignId: String, accountId: String) =
        attendance.filter { it.first == campaignId && it.second == accountId }.map { it.third }.sorted()
    override fun referralCode(accountId: String, newCode: () -> String) = codes.getOrPut(accountId, newCode)
    override fun accountOfCode(code: String) = codes.entries.firstOrNull { it.value == code }?.key
    override fun insertReferral(referral: Referral): Boolean = referrals.putIfAbsent(referral.inviteeId, referral) == null
    override fun referralOfInvitee(inviteeId: String) = referrals[inviteeId]
    override fun countReferrals(inviterId: String, campaignId: String?, status: ReferralStatus?) =
        referrals.values.count { it.inviterId == inviterId && (campaignId == null || it.campaignId == campaignId) && (status == null || it.status == status) }.toLong()
    override fun settleReferral(inviteeId: String, to: ReferralStatus, at: Instant): Boolean {
        val r = referrals[inviteeId]?.takeIf { it.status == ReferralStatus.CLAIMED } ?: return false
        referrals[inviteeId] = r.copy(status = to, qualifiedAt = at)
        return true
    }
    override fun deleteByAccount(accountId: String) {
        attendance.removeIf { it.second == accountId }
        codes.remove(accountId)
        referrals.values.removeIf { it.inviteeId == accountId || it.inviterId == accountId }
    }
}

class RecordingGrants : RewardGrants {
    val recorded = mutableListOf<Triple<String, String, String>>()
    override fun record(accountId: String, type: String, params: Map<String, String>, sourceType: String, sourceKey: String): String? {
        if (recorded.any { it.third == "$sourceType/$sourceKey" }) return null
        recorded += Triple(accountId, type, "$sourceType/$sourceKey")
        return "g${recorded.size}"
    }
}

class PointIssuer : RewardIssuer {
    override val type = "point"
    override fun validate(params: Map<String, String>) = require(params["amount"]?.toLongOrNull()?.let { it > 0 } == true) { "amount must be a positive number" }
    override fun issue(grant: RewardGrantView) = IssuedReward("res")
    override fun describe(params: Map<String, String>, locale: Locale) = RewardLabel("Points", "p")
}
