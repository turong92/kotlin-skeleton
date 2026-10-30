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
    override fun referralOfInvitee(inviteeId: String, lock: Boolean) = referrals[inviteeId]
    override fun lockAccounts(accountIds: Collection<String>) = Unit
    override fun countReferrals(inviterId: String, campaignId: String?, status: ReferralStatus?, lock: Boolean) =
        referrals.values.count { it.inviterId == inviterId && (campaignId == null || it.campaignId == campaignId) && (status == null || it.status == status) }.toLong()
    override fun countReferralsIn(inviterId: String, campaignId: String?, statuses: Set<ReferralStatus>, lock: Boolean) =
        referrals.values.count { it.inviterId == inviterId && (campaignId == null || it.campaignId == campaignId) && it.status in statuses }.toLong()
    override fun transitionReferral(
        inviteeId: String, from: Set<ReferralStatus>, to: ReferralStatus, at: Instant,
        releaseAt: Instant?, qualifyEvent: String?, settledBy: String?, cancelReason: String?,
    ): Boolean {
        val r = referrals[inviteeId]?.takeIf { it.status in from } ?: return false
        val ended = to == ReferralStatus.QUALIFIED || to == ReferralStatus.CAPPED || to == ReferralStatus.CANCELED
        referrals[inviteeId] = r.copy(
            status = to,
            qualifiedAt = if (to == ReferralStatus.QUALIFIED) at else r.qualifiedAt,
            releaseAt = releaseAt ?: r.releaseAt,
            qualifyEvent = qualifyEvent ?: r.qualifyEvent,
            settledAt = if (ended) at else r.settledAt,
            settledBy = if (ended) settledBy else r.settledBy,
            cancelReason = if (to == ReferralStatus.CANCELED) cancelReason else r.cancelReason,
        )
        return true
    }
    override fun dueHeldReferrals(now: Instant, limit: Int) =
        referrals.values.filter { it.status == ReferralStatus.HELD && it.releaseAt != null && !it.releaseAt.isAfter(now) }.map { it.inviteeId }.take(limit)

    /** 초대자 코드 줄에 적힌 마지막 네트워크 */
    val codeNetworks = mutableMapOf<String, Pair<String, Instant>>()
    override fun recordInviterNetwork(accountId: String, network: String, at: Instant) {
        if (codes.containsKey(accountId)) codeNetworks[accountId] = network to at
    }
    override fun networkSeen(inviterId: String, network: String, since: Instant): Boolean =
        codeNetworks[inviterId]?.let { it.first == network && !it.second.isBefore(since) } == true ||
            referrals.values.any { it.inviterId == inviterId && it.claimNetwork == network && !it.claimedAt.isBefore(since) }
    override fun clearNetworksBefore(before: Instant): Int {
        var n = 0
        codeNetworks.entries.removeIf { (_, v) -> v.second.isBefore(before).also { if (it) n++ } }
        referrals.replaceAll { _, r -> if (r.claimNetwork != null && r.claimedAt.isBefore(before)) r.copy(claimNetwork = null).also { n++ } else r }
        return n
    }
    val progress = mutableMapOf<Pair<String, String>, Long>()
    override fun upsertProgressMax(accountId: String, metric: String, value: Long, at: Instant): Long =
        progress.merge(accountId to metric, value) { old, new -> maxOf(old, new) }!!
    override fun progress(accountId: String, metric: String): Long? = progress[accountId to metric]
    override fun eraseAccount(accountId: String, tombstone: String) {
        progress.keys.removeIf { it.first == accountId }
        attendance.removeIf { it.second == accountId }
        codes.remove(accountId)
        codeNetworks.remove(accountId)
        referrals.values.removeIf { it.inviteeId == accountId }
        referrals.replaceAll { _, r -> if (r.inviterId == accountId) r.copy(inviterId = tombstone) else r }
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
