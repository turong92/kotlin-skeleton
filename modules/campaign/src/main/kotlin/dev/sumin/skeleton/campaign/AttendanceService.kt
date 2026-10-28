package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardGrants
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

data class RewardLine(val type: String, val label: String)

data class CheckInResult(val alreadyChecked: Boolean, val total: Int, val streak: Int, val rewards: List<RewardLine>)

data class Milestone(val days: Int, val streak: Boolean)

data class AttendanceSummary(val checkedToday: Boolean, val total: Int, val streak: Int, val nextMilestone: Milestone?, val today: LocalDate)

/**
 * 출석 체크 - 하루 한 번은 `(campaign, account, day)` 키가 지키고, 「오늘」은 **서비스 시간대**로 센다([CampaignZone]).
 * 한 트랜잭션: 출석 줄 insert-ignore(이미 있으면 지급 없이 현재 상태) → 누적 · 연속 계산 → 매일 보상 + 이번에 닿은 달성 보상마다 [RewardGrants.record]. **여기서 끝** -
 * 쿠폰 · 포인트 표를 만지지 않는다(일꾼이 따로 준다). 지급 건의 `source_key` 가 같은 조건의 두 번 판정을 한 건으로 접는다.
 */
class AttendanceService(
    private val store: CampaignStore,
    private val grants: RewardGrants,
    private val issuers: () -> List<RewardIssuer>,
    private val zone: CampaignZone,
    private val time: TimeProvider,
    private val locale: Locale = Locale.getDefault(),
) {
    fun checkIn(accountId: String, campaignId: String): CheckInResult = store.inTransaction {
        val campaign = attendanceCampaign(campaignId)
        val config = AttendanceConfig.parse(campaign.config)
        val now = time.now()
        val today = zone.today(now)
        val inserted = store.insertAttendance(campaign.id, accountId, today, now)
        val days = store.attendanceDays(campaign.id, accountId)
        val total = days.size
        val streak = streakEndingOn(days, today)
        if (!inserted) {
            CheckInResult(true, total, streak, emptyList())
        } else {
            val earned = mutableListOf<RewardSpec>()
            config.daily.forEachIndexed { i, spec ->
                if (grants.record(accountId, spec.type, spec.params, SOURCE_TYPE, "${campaign.id}:$accountId:$today:daily:$i") != null) earned += spec
            }
            config.milestones.forEach { m ->
                // 누적 달성은 한 번, 연속 달성은 연속이 끊겼다 다시 쌓여 닿을 때마다(연속의 시작일이 키에 든다)
                val key = when {
                    !m.streak && total == m.days -> "${campaign.id}:$accountId:milestone:${m.days}"
                    m.streak && streak == m.days -> "${campaign.id}:$accountId:streak:${m.days}:${today.minusDays(streak - 1L)}"
                    else -> null
                }
                if (key != null) m.rewards.forEachIndexed { i, spec ->
                    if (grants.record(accountId, spec.type, spec.params, SOURCE_TYPE, "$key:$i") != null) earned += spec
                }
            }
            CheckInResult(false, total, streak, earned.map { RewardLine(it.type, label(it)) })
        }
    }

    /** 진행 중인 출석 이벤트에서 내 상태 */
    fun summary(accountId: String, campaign: Campaign): AttendanceSummary {
        val today = zone.today(time.now())
        val days = store.attendanceDays(campaign.id, accountId)
        val total = days.size
        val streak = streakEndingOn(days, today)
        val milestones = AttendanceConfig.parse(campaign.config).milestones
        val next = milestones
            .map { it to (it.days - if (it.streak) streak else total) }
            .filter { it.second > 0 }
            .minWithOrNull(compareBy({ it.second }, { it.first.streak }))?.first
        return AttendanceSummary(today in days, total, streak, next?.let { Milestone(it.days, it.streak) }, today)
    }

    /** 이달(`YYYY-MM`)에 출석한 날들 */
    fun month(accountId: String, campaignId: String, month: YearMonth): List<LocalDate> {
        val campaign = store.find(campaignId)?.takeIf { it.kind == AttendanceKind.KIND } ?: throw CampaignException(CampaignErrorCode.NOT_FOUND, "campaign $campaignId not found")
        return store.attendanceDays(campaign.id, accountId).filter { YearMonth.from(it) == month }
    }

    private fun attendanceCampaign(id: String): Campaign {
        val campaign = store.find(id)?.takeIf { it.kind == AttendanceKind.KIND } ?: throw CampaignException(CampaignErrorCode.NOT_FOUND, "campaign $id not found")
        if (!campaign.isActive(time.now())) throw CampaignException(CampaignErrorCode.NOT_ACTIVE, "campaign $id is not active")
        return campaign
    }

    private fun label(spec: RewardSpec): String =
        runCatching { issuers().firstOrNull { it.type == spec.type }?.describe(spec.params, locale)?.title }.getOrNull() ?: spec.type

    private fun streakEndingOn(days: List<LocalDate>, today: LocalDate): Int {
        val set = days.toHashSet()
        var count = 0
        var day = today
        while (day in set) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    companion object {
        const val SOURCE_TYPE = "attendance"
    }
}
