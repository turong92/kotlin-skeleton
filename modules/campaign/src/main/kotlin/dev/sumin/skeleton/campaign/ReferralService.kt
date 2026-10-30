package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardGrants
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * 앱이 초대 관계에 알리는 자리.
 * - [qualify]: 「의미 있는 행동」(예: 첫 결제) - `qualifyOn: SIGNAL` 인 초대 이벤트의 초대자 보상이 이때 정해진다. 이벤트가 `qualifyEvent` 를 정했으면 [event] 가 같은 신호만 먹는다. 몇 번 불러도 한 번만 먹는다.
 * - [cancel]: 보류 중(환불 · 사기)인 관계를 끊는다. 대기 · 확인 · 보류 중일 때만 먹고, 이미 끝났거나 없으면 `false`.
 * - [cancelPending]: [cancel] 인데 `HELD` · `REVIEW` 만 - 신호 전 관계는 건드리지 않는다.
 * - [releaseDue]: 보류 기한이 지난 관계에 초대자 보상을 준다(그리고 30일 지난 네트워크 값을 지운다). `scheduler` 가 있으면 주기 작업이 부르고, 없으면 **보류를 안 써도** 앱이 주기적으로 불러야 네트워크 값이 지워진다.
 */
interface Referrals {
    /** 이 호출이 관계를 신호 대기(`CLAIMED`)에서 움직였으면 `true` - 앱이 「자격을 만든 그 주문」을 기억하는 데 쓴다. 이미 판정됐거나 먹지 않은 신호는 `false` */
    fun qualify(inviteeId: String, event: String?): Boolean

    /** 이름 없는 신호 - `qualifyEvent` 를 정하지 않은 이벤트에서만 먹는다 */
    fun qualify(inviteeId: String): Boolean = qualify(inviteeId, null)

    fun cancel(inviteeId: String, reason: String): Boolean

    /** [cancel] 과 같되 이미 보류(`HELD`) · 확인 중(`REVIEW`)인 관계만 끊는다 - 아직 신호 전(`CLAIMED`)인 관계는 그대로 둔다 */
    fun cancelPending(inviteeId: String, reason: String): Boolean

    /** 보상을 준 관계 수 */
    fun releaseDue(): Int
}

/** [canClaim]: 진행 중인 초대 이벤트가 있고 아직 초대받지 않았고 가입 후 기간 안. [claimUntil]: 코드를 쓸 수 있는 마지막 때 - 진행 중인 이벤트가 있고 [AccountFacts] 가 있을 때만 */
data class ReferralSummary(val code: String, val invited: Int, val rewarded: Int, val canClaim: Boolean = false, val claimUntil: Instant? = null)

/** 목록 한 쪽 - 다음 쪽이 있으면 [nextCursor] */
data class ReferralPage(val items: List<Referral>, val nextCursor: String?)

/**
 * 친구초대. 코드는 계정당 하나(처음 볼 때 만든다). claim 한 트랜잭션에서 판정한다: 진행 중인 초대 이벤트 · 없는 코드 · 자기 코드 · 이미 초대받음 · 맞초대 ·
 * 새 계정(가입 후 `claimWithinDays` 안, [AccountFacts] 가 없으면 이 판정만 건너뛴다) — 통과하면 관계 줄 + 초대받은 사람 보상 지급 건.
 * 초대자 보상은 `CLAIM` 이면 같은 트랜잭션, `SIGNAL` 이면 [qualify] 때 **초대자 판정**으로 정한다(초대자 코드 줄을 잠근 채, 위에서부터 처음 맞는 것):
 * 상한([ReferralConfig.inviterCap], `QUALIFIED + HELD + REVIEW` 가 자리를 찬다) → `CAPPED` / 같은 네트워크 확인([ReferralConfig.reviewSameNetwork]) → `REVIEW` /
 * 보류([ReferralConfig.holdDays]) → `HELD` / 그 밖 → `QUALIFIED` + 초대자 보상. `HELD` 는 [releaseDue], `REVIEW` · `HELD` 는 [approve] 로 풀린다.
 */
class ReferralService(
    private val store: CampaignStore,
    private val campaigns: Campaigns,
    private val grants: RewardGrants,
    private val facts: AccountFacts?,
    private val time: TimeProvider,
    private val random: SecureRandom = SecureRandom(),
) : Referrals {
    /** [network] 가 있으면 초대자의 마지막 네트워크로 적는다 - 같은 네트워크 표시의 재료 */
    fun me(accountId: String, network: String? = null): ReferralSummary {
        val code = store.inTransaction {
            store.referralCode(accountId, ::newCode).also { if (network != null) store.recordInviterNetwork(accountId, network.take(MAX_NETWORK), time.now()) }
        }
        val campaign = campaigns.active().firstOrNull { it.kind == ReferralKind.KIND }
        val claimUntil = campaign?.let { c -> facts?.createdAt(accountId)?.plus(Duration.ofDays(ReferralConfig.parse(c.config).claimWithinDays.toLong())) }
        val canClaim = campaign != null && store.referralOfInvitee(accountId) == null && (facts == null || (claimUntil != null && !claimUntil.isBefore(time.now())))
        return ReferralSummary(
            code, store.countReferrals(accountId, null, null).toInt(), store.countReferrals(accountId, null, ReferralStatus.QUALIFIED).toInt(),
            canClaim, claimUntil,
        )
    }

    /** 내가 초대한 사람들, 새것부터 */
    fun invitees(inviterId: String, cursor: String?, size: Int): ReferralPage = list(ReferralFilter(inviterId = inviterId), cursor, size)

    /** 운영 목록 - [filter] 에 맞는 초대 내역, 새것부터. [cursor] 는 앞 쪽의 [ReferralPage.nextCursor] */
    fun list(filter: ReferralFilter, cursor: String?, size: Int): ReferralPage {
        val limit = size.coerceIn(1, MAX_PAGE)
        val rows = store.listReferrals(filter, cursor?.let(::decodeCursor), limit + 1)
        val page = rows.take(limit)
        return ReferralPage(page, if (rows.size > limit) page.last().let { encodeCursor(it.claimedAt, it.inviteeId) } else null)
    }

    private fun encodeCursor(at: Instant, id: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray())

    private fun decodeCursor(cursor: String): ReferralPosition = try {
        val (at, id) = String(Base64.getUrlDecoder().decode(cursor)).split('|', limit = 2)
        ReferralPosition(Instant.parse(at), id)
    } catch (e: RuntimeException) {   // 잘못된 base64 · 모양 · 시각 모두
        throw ApplicationException("cursor is not valid", PlatformErrorCode.PARAMETER_VALIDATION_FAILED)
    }

    /** [network] 는 호출자의 네트워크 키(platform `ClientIps` 의 `limitKey`) - 모르면 null */
    fun claim(inviteeId: String, rawCode: String, network: String? = null) {
        store.inTransaction {
            val campaign = campaigns.active().firstOrNull { it.kind == ReferralKind.KIND } ?: throw CampaignException(CampaignErrorCode.NOT_ACTIVE, "no referral campaign is running")
            val config = ReferralConfig.parse(campaign.config)
            val now = time.now()
            val inviterId = store.accountOfCode(rawCode.trim().uppercase()) ?: reject(ReferralRejection.UNKNOWN_CODE)
            if (inviterId == inviteeId) reject(ReferralRejection.OWN_CODE)
            // 초대자 상한과 맞초대(A↔B 동시 claim)는 세고 넣는 사이에 끼어들면 뚫린다 - 두 계정의 코드 줄을 정렬된 순서로 먼저 잡아 직렬화하고, 이후 판정 읽기는 잠금 읽기로 최신 값을 본다
            store.referralCode(inviteeId, ::newCode)
            store.lockAccounts(listOf(inviterId, inviteeId))
            if (store.referralOfInvitee(inviteeId, lock = true) != null) reject(ReferralRejection.ALREADY_INVITED)
            if (store.referralOfInvitee(inviterId, lock = true)?.inviterId == inviteeId) reject(ReferralRejection.MUTUAL)
            if (facts != null) {
                val createdAt = facts.createdAt(inviteeId)
                if (createdAt == null || createdAt.plus(Duration.ofDays(config.claimWithinDays.toLong())).isBefore(now)) reject(ReferralRejection.NOT_NEW_ACCOUNT)
            }
            // 같은 네트워크는 거절이 아니라 표시다 - 초대받은 사람 보상은 그대로 나간다
            val claimNetwork = network?.take(MAX_NETWORK)
            val flags = if (claimNetwork != null && store.networkSeen(inviterId, claimNetwork, now.minus(NETWORK_WINDOW))) setOf(ReferralFlag.SAME_NETWORK) else emptySet()
            val verdict = if (config.qualifyOn == QualifyOn.CLAIM) judge(inviterId, campaign.id, config, flags, now) else null
            val status = verdict?.status ?: ReferralStatus.CLAIMED
            val referral = Referral(
                inviteeId, inviterId, campaign.id, status, now, if (status == ReferralStatus.QUALIFIED) now else null,
                releaseAt = verdict?.releaseAt, settledAt = if (verdict?.final == true) now else null, flags = flags, claimNetwork = claimNetwork,
            )
            if (!store.insertReferral(referral)) reject(ReferralRejection.ALREADY_INVITED)   // 같은 사람이 동시에 두 번 - 평생 한 번
            if (status != ReferralStatus.CAPPED) {
                record(inviteeId, config.invitee, campaign.id, inviteeId, "invitee")
                if (status == ReferralStatus.QUALIFIED) record(inviterId, config.inviter, campaign.id, inviteeId, "inviter")
            }
        }
    }

    override fun qualify(inviteeId: String, event: String?): Boolean {
        return store.inTransaction {
            val first = store.referralOfInvitee(inviteeId) ?: return@inTransaction false
            if (first.status != ReferralStatus.CLAIMED || AccountTombstone.isTombstone(first.inviterId)) return@inTransaction false   // 탈퇴한 초대자에게는 줄 곳이 없다
            val config = ReferralConfig.parse((store.find(first.campaignId) ?: return@inTransaction false).config)
            if (config.qualifyEvent != null && config.qualifyEvent != event) return@inTransaction false   // 이벤트가 고른 신호만 먹는다
            store.lockAccounts(listOf(first.inviterId))   // 같은 초대자의 상한 판정을 직렬화한다
            val referral = store.referralOfInvitee(inviteeId, lock = true) ?: return@inTransaction false
            if (referral.status != ReferralStatus.CLAIMED) return@inTransaction false
            val now = time.now()
            val verdict = judge(referral.inviterId, referral.campaignId, config, referral.flags, now)
            val moved = store.transitionReferral(inviteeId, setOf(ReferralStatus.CLAIMED), verdict.status, now, releaseAt = verdict.releaseAt, qualifyEvent = event)
            if (moved && verdict.status == ReferralStatus.QUALIFIED) record(referral.inviterId, config.inviter, referral.campaignId, inviteeId, "inviter")
            moved
        }
    }

    override fun releaseDue(): Int {
        val now = time.now()
        val released = store.dueHeldReferrals(now, RELEASE_BATCH).count { release(it, now) }
        store.clearNetworksBefore(now.minus(NETWORK_WINDOW))
        return released
    }

    private fun release(inviteeId: String, now: Instant): Boolean = store.inTransaction {
        val referral = store.referralOfInvitee(inviteeId, lock = true)
        if (referral == null || referral.status != ReferralStatus.HELD || referral.releaseAt == null || referral.releaseAt.isAfter(now)) return@inTransaction false
        if (AccountTombstone.isTombstone(referral.inviterId)) {   // 탈퇴한 초대자에게는 줄 곳이 없다 - 계속 기한 목록에 남지 않게 닫는다
            store.transitionReferral(inviteeId, setOf(ReferralStatus.HELD), ReferralStatus.CANCELED, now, cancelReason = INVITER_LEFT)
            return@inTransaction false
        }
        pay(referral, now, settledBy = null, from = setOf(ReferralStatus.HELD))
    }

    /** 운영자 승인 - `REVIEW | HELD` 의 초대자 보상을 지금 준다(남은 보류를 건너뛴다). 그 밖의 상태는 409 */
    fun approve(inviteeId: String, operatorId: String) {
        store.inTransaction {
            val referral = store.referralOfInvitee(inviteeId, lock = true) ?: throw CampaignException(CampaignErrorCode.REFERRAL_NOT_FOUND, "referral of $inviteeId not found")
            if (referral.status !in ReferralStatus.APPROVABLE) throw CampaignException(CampaignErrorCode.REFERRAL_NOT_PENDING, "referral of $inviteeId is ${referral.status}")
            if (AccountTombstone.isTombstone(referral.inviterId)) throw CampaignException(CampaignErrorCode.REFERRAL_NOT_PENDING, "the inviter of $inviteeId has left")
            pay(referral, time.now(), operatorId, ReferralStatus.APPROVABLE)
        }
    }

    /** 운영자 취소 - `CLAIMED | REVIEW | HELD` → `CANCELED`. 사유 필수(1..200자), 끝 상태는 409 */
    fun cancelByOperator(inviteeId: String, reason: String, operatorId: String) {
        cancelRelation(inviteeId, reason, operatorId, strict = true)
    }

    override fun cancel(inviteeId: String, reason: String): Boolean = cancelRelation(inviteeId, reason, APP, strict = false)

    override fun cancelPending(inviteeId: String, reason: String): Boolean = cancelRelation(inviteeId, reason, APP, strict = false, from = ReferralStatus.APPROVABLE)

    private fun cancelRelation(inviteeId: String, reason: String, by: String, strict: Boolean, from: Set<ReferralStatus> = ReferralStatus.CANCELABLE): Boolean {
        val text = reason.trim()
        if (text.isEmpty() || text.length > MAX_REASON) throw CampaignException(CampaignErrorCode.REFERRAL_REASON_INVALID, "reason must be 1..$MAX_REASON characters")
        return store.inTransaction {
            val referral = store.referralOfInvitee(inviteeId, lock = true)
            if (referral == null || referral.status !in from) {
                if (strict) throw CampaignException(if (referral == null) CampaignErrorCode.REFERRAL_NOT_FOUND else CampaignErrorCode.REFERRAL_NOT_CANCELABLE, "referral of $inviteeId cannot be canceled")
                return@inTransaction false
            }
            store.transitionReferral(inviteeId, from, ReferralStatus.CANCELED, time.now(), settledBy = by, cancelReason = text)
        }
    }

    private class Verdict(val status: ReferralStatus, val releaseAt: Instant? = null) {
        val final: Boolean get() = status == ReferralStatus.QUALIFIED || status == ReferralStatus.CAPPED
    }

    /** 초대자 판정 - 초대자 코드 줄을 잠근 호출자 트랜잭션 안에서만. 위에서부터 처음 맞는 것 */
    private fun judge(inviterId: String, campaignId: String, config: ReferralConfig, flags: Set<ReferralFlag>, now: Instant): Verdict = when {
        config.inviterCap != null && store.countReferralsIn(inviterId, campaignId, ReferralStatus.SLOT_TAKING, lock = true) >= config.inviterCap -> Verdict(ReferralStatus.CAPPED)
        config.reviewSameNetwork && ReferralFlag.SAME_NETWORK in flags -> Verdict(ReferralStatus.REVIEW)
        config.holdDays > 0 -> Verdict(ReferralStatus.HELD, now.plus(Duration.ofDays(config.holdDays.toLong())))
        else -> Verdict(ReferralStatus.QUALIFIED)
    }

    /** 보류 · 확인 중이던 관계의 초대자 보상을 준다 - 조건부 갱신이라 승인과 해제가 겹쳐도 한 번 */
    private fun pay(referral: Referral, now: Instant, settledBy: String?, from: Set<ReferralStatus>): Boolean {
        val config = ReferralConfig.parse((store.find(referral.campaignId) ?: return false).config)
        if (!store.transitionReferral(referral.inviteeId, from, ReferralStatus.QUALIFIED, now, settledBy = settledBy)) return false
        record(referral.inviterId, config.inviter, referral.campaignId, referral.inviteeId, "inviter")
        return true
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
        /** 앱이 [Referrals.cancel] 로 끊었을 때 `settled_by` */
        const val APP = "app"
        const val INVITER_LEFT = "inviter_left"
        const val MAX_REASON = 200
        const val MAX_NETWORK = 64
        const val DEFAULT_PAGE = 20
        const val MAX_PAGE = 100
        private const val RELEASE_BATCH = 500
        /** 네트워크 값을 판정에 쓰고 보관하는 기간 - 지나면 해제 작업이 지운다 */
        val NETWORK_WINDOW: Duration = Duration.ofDays(30)
        const val CODE_LENGTH = 8
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
