package dev.sumin.skeleton.campaign

import java.time.Instant
import java.time.LocalDate

data class Campaign(
    val id: String,
    val kind: String,
    val name: String,
    val enabled: Boolean,
    val startsAt: Instant,
    val endsAt: Instant?,
    /** 종류별 설정 (JSON 으로 저장). 모양은 [CampaignKind] 가 정한다 */
    val config: Map<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** 켜져 있고 기간 안인가 — 시작은 포함, 끝은 미포함 */
    fun isActive(now: Instant): Boolean = enabled && !now.isBefore(startsAt) && (endsAt == null || now.isBefore(endsAt))
}

/** 초대 관계 상태. `CLAIMED` — 초대자 보상을 기다린다(`SIGNAL`), `QUALIFIED` — 초대자 보상이 정해졌다, `CAPPED` — 초대자 상한에 걸려 보상 없음 */
enum class ReferralStatus { CLAIMED, QUALIFIED, CAPPED }

data class Referral(
    val inviteeId: String,
    val inviterId: String,
    val campaignId: String,
    val status: ReferralStatus,
    val claimedAt: Instant,
    val qualifiedAt: Instant?,
)

/**
 * 이벤트 저장 포트 — `campaign-jdbc` 가 구현한다. 쓰기는 [inTransaction] 안(또는 호출자 트랜잭션 안)에서 부른다.
 */
interface CampaignStore {
    /** 열린 트랜잭션이 있으면 거기에 합류하고, 없으면 새로 연다 */
    fun <T> inTransaction(block: () -> T): T

    fun insert(campaign: Campaign)

    /** 이름 · 기간 · 설정을 바꾼다(종류는 못 바꾼다). 없으면 false */
    fun update(campaign: Campaign): Boolean

    fun setEnabled(id: String, enabled: Boolean, at: Instant): Boolean

    fun find(id: String): Campaign?

    /** 모두, 새것부터 */
    fun list(): List<Campaign>

    /** 하루 한 번 — `(campaign, account, day)` 키가 지킨다. 새로 넣었으면 true */
    fun insertAttendance(campaignId: String, accountId: String, day: LocalDate, at: Instant): Boolean

    /** 이 계정이 출석한 날들, 오래된 것부터 */
    fun attendanceDays(campaignId: String, accountId: String): List<LocalDate>

    /** 계정의 초대 코드 — 없으면 [newCode] 로 만들어 저장한다 */
    fun referralCode(accountId: String, newCode: () -> String): String

    fun accountOfCode(code: String): String?

    /** 초대받은 사람은 평생 한 번 - `invitee_id` 키가 지킨다. 새로 넣었으면 true */
    fun insertReferral(referral: Referral): Boolean

    fun referralOfInvitee(inviteeId: String): Referral?

    /** 이 초대자의 관계 수. [status] 가 null 이면 전부 */
    fun countReferrals(inviterId: String, campaignId: String?, status: ReferralStatus?): Long

    /** `CLAIMED` 일 때만 [to] 로 옮긴다. 옮겼으면 true */
    fun settleReferral(inviteeId: String, to: ReferralStatus, at: Instant): Boolean

    /** 계정이 지워질 때 출석 · 코드 · 초대 관계(초대자든 초대받은 쪽이든)를 지운다 */
    fun deleteByAccount(accountId: String)
}
