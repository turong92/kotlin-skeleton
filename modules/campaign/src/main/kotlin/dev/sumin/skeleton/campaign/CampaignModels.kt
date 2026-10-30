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

/**
 * 초대 관계 상태. `CLAIMED` — 초대자 보상을 기다린다(`SIGNAL`), `QUALIFIED` — 초대자 보상이 정해졌다, `CAPPED` — 초대자 상한에 걸려 보상 없음,
 * `REVIEW` — 운영자 확인을 기다린다(자동으로 풀리지 않는다), `HELD` — 보류 기한을 기다린다, `CANCELED` — 보류 중 취소(보상 없음).
 * 끝 상태는 `QUALIFIED` · `CAPPED` · `CANCELED` - 바뀌지 않는다.
 */
enum class ReferralStatus {
    CLAIMED, QUALIFIED, CAPPED, REVIEW, HELD, CANCELED;

    companion object {
        /** 초대자 상한에서 자리를 차지하는 상태 - 보류 · 확인 중도 자리를 차지하므로 해제 때 다시 세지 않아도 상한을 넘지 않는다 */
        val SLOT_TAKING: Set<ReferralStatus> = setOf(QUALIFIED, HELD, REVIEW)

        /** 취소할 수 있는 상태 */
        val CANCELABLE: Set<ReferralStatus> = setOf(CLAIMED, REVIEW, HELD)

        /** 운영자 승인으로 초대자 보상을 지급할 수 있는 상태 */
        val APPROVABLE: Set<ReferralStatus> = setOf(REVIEW, HELD)
    }
}

/** 초대 관계에 붙는 표시 - 거절이 아니라 운영자가 보는 단서다. 사용자 응답에는 내보내지 않는다 */
enum class ReferralFlag {
    /** 초대받은 사람의 claim 네트워크가 초대자의 마지막 네트워크 또는 같은 초대자의 다른 초대받은 사람의 것과 같다 (30일 안) */
    SAME_NETWORK,
}

data class Referral(
    val inviteeId: String,
    val inviterId: String,
    val campaignId: String,
    val status: ReferralStatus,
    val claimedAt: Instant,
    val qualifiedAt: Instant?,
    /** 초대자 보상을 정한 신호의 이름(이름 없이 불렀으면 null) */
    val qualifyEvent: String? = null,
    /** 보류가 끝나는 때 - `HELD` 였던 적이 있으면 */
    val releaseAt: Instant? = null,
    /** `QUALIFIED` · `CAPPED` · `CANCELED` 가 된 때 */
    val settledAt: Instant? = null,
    /** 승인 · 취소한 운영자 id, 앱이면 `app`, 자동이면 null */
    val settledBy: String? = null,
    val cancelReason: String? = null,
    val flags: Set<ReferralFlag> = emptySet(),
    /** claim 때의 네트워크 키 - 30일 뒤 지워진다(판정 결과는 [flags] 로만 남는다) */
    val claimNetwork: String? = null,
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

    /** [lock] 이면 잠금 읽기(`for update`) - 다른 트랜잭션이 방금 커밋한 값을 본다. 상한 · 맞초대 판정이 쓴다 */
    fun referralOfInvitee(inviteeId: String, lock: Boolean = false): Referral?

    /** 이 초대자의 관계 수. [status] 가 null 이면 전부. [lock] 이면 센 줄을 잠그고 최신 값으로 센다 */
    fun countReferrals(inviterId: String, campaignId: String?, status: ReferralStatus?, lock: Boolean = false): Long

    /**
     * 계정들의 초대 코드 줄을 **정렬된 순서로** 잠근다 - 초대자 단위(상한)와 두 계정 사이(맞초대) 판정을 직렬화하고, 순서가 고정이라 교착하지 않는다.
     * 줄이 없는 계정은 건너뛴다(먼저 [referralCode] 로 만든다). 호출자 트랜잭션 안에서만 뜻이 있다.
     */
    fun lockAccounts(accountIds: Collection<String>)

    /** 이 초대자의 관계 수 - 상태가 [statuses] 중 하나인 것. [lock] 이면 [countReferrals] 와 같다 */
    fun countReferralsIn(inviterId: String, campaignId: String?, statuses: Set<ReferralStatus>, lock: Boolean = false): Long

    /**
     * 상태가 [from] 중 하나일 때만 [to] 로 옮긴다(조건부 갱신 - 겹쳐 불러도 한 번만 true). 옮겼으면 true.
     * [to] 가 `QUALIFIED` 면 `qualified_at`, 끝 상태면 `settled_at` · [settledBy] · (취소면) [cancelReason] 을 적고,
     * [releaseAt] · [qualifyEvent] 는 null 이 아닐 때만 적는다.
     */
    fun transitionReferral(
        inviteeId: String,
        from: Set<ReferralStatus>,
        to: ReferralStatus,
        at: Instant,
        releaseAt: Instant? = null,
        qualifyEvent: String? = null,
        settledBy: String? = null,
        cancelReason: String? = null,
    ): Boolean

    /** 보류 기한이 지난(`HELD` ∧ `release_at <= now`) 초대받은 사람 id, 기한이 빠른 것부터 */
    fun dueHeldReferrals(now: Instant, limit: Int): List<String>

    /** 초대자가 `GET /referrals/me` 를 부른 네트워크를 코드 줄에 적는다. 줄이 없으면 아무것도 안 한다 */
    fun recordInviterNetwork(accountId: String, network: String, at: Instant)

    /** [network] 가 초대자의 마지막 네트워크이거나 같은 초대자의 다른 초대받은 사람의 claim 네트워크인가 - [since] 이후 것만 */
    fun networkSeen(inviterId: String, network: String, since: Instant): Boolean

    /** [before] 이전의 네트워크 값(코드 줄의 마지막 네트워크 · 관계의 claim 네트워크)을 지운다. 지운 값의 수 */
    fun clearNetworksBefore(before: Instant): Int

    /** 계정의 수치 [metric] 을 `max(기존, value)` 로 올리고(증가만) 올린 뒤의 값을 돌려준다. 같은 수치를 동시에 올려도 큰 쪽이 남는다 */
    fun upsertProgressMax(accountId: String, metric: String, value: Long, at: Instant): Long

    fun progress(accountId: String, metric: String): Long?

    /**
     * 계정이 지워질 때: 출석 · 업적 수치 · 코드 · **초대받은 쪽** 관계는 지우고, **초대자 쪽** 관계는 지우지 않고 초대자 id 만 [tombstone] 으로 바꾼다 -
     * 초대받은 사람의 「평생 한 번」이 풀리지 않게(지우면 다른 코드로 다시 claim 할 수 있다).
     */
    fun eraseAccount(accountId: String, tombstone: String)
}
