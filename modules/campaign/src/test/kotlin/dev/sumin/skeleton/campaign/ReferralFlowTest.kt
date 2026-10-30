package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.reward.RewardGrants
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MutableClock(var now: Instant) : TimeProvider {
    override fun now(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

/** 초대자 판정 순서(상한 → 같은 네트워크 확인 → 보류 → 지급) · 신호 이름 · 해제 · 승인 · 취소 · 네트워크 표시 */
class ReferralFlowTest {
    private val start = Instant.parse("2026-10-30T00:00:00Z")
    private val clock = MutableClock(start)
    private val store = FakeCampaignStore()
    private val grants = RecordingGrants()
    private val campaigns = Campaigns(store, { listOf(ReferralKind()) }, { listOf(PointIssuer()) }, clock)
    private val service = ReferralService(store, campaigns, grants, null, clock)
    private val point = listOf(mapOf("type" to "point", "params" to mapOf("amount" to "50")))

    private fun campaign(vararg extra: Pair<String, Any?>): String =
        campaigns.create("referral", CampaignInput("Invite", start.minusSeconds(1), null, mapOf("invitee" to point, "inviter" to point) + extra)).id

    private fun invite(inviter: String, invitee: String, network: String? = null) {
        service.claim(invitee, service.me(inviter).code, network)
    }

    private fun inviterGrants() = grants.recorded.filter { it.third.contains(":inviter:") }
    private fun status(invitee: String) = store.referralOfInvitee(invitee)!!.status
    private fun code(block: () -> Unit): ErrorCode = assertFailsWith<CampaignException> { block() }.errorCode

    // 신호 이름

    @Test
    fun `a signal with another name or no name is ignored when the campaign names its signal`() {
        campaign("qualifyOn" to "SIGNAL", "qualifyEvent" to "first-payment")
        invite("alice", "bob")

        service.qualify("bob", "email-verified")
        service.qualify("bob", null)
        service.qualify("bob")
        assertEquals(ReferralStatus.CLAIMED, status("bob"))

        service.qualify("bob", "first-payment")
        service.qualify("bob", "first-payment")
        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
        assertEquals("first-payment", store.referralOfInvitee("bob")!!.qualifyEvent)
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `a campaign without a signal name takes any signal, as before`() {
        campaign("qualifyOn" to "SIGNAL")
        invite("alice", "bob"); invite("alice", "carol")

        service.qualify("bob")
        service.qualify("carol", "whatever")

        assertEquals(listOf(ReferralStatus.QUALIFIED, ReferralStatus.QUALIFIED), listOf(status("bob"), status("carol")))
    }

    // 보류 · 해제

    @Test
    fun `holdDays holds the inviter reward until the date, then one release pays once`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7)
        invite("alice", "bob")
        service.qualify("bob")

        val held = store.referralOfInvitee("bob")!!
        assertEquals(ReferralStatus.HELD, held.status)
        assertEquals(start.plus(Duration.ofDays(7)), held.releaseAt)
        assertEquals(0, inviterGrants().size)

        clock.advance(Duration.ofDays(7).minusSeconds(1))
        assertEquals(0, service.releaseDue())
        assertEquals(ReferralStatus.HELD, status("bob"))

        clock.advance(Duration.ofSeconds(1))
        assertEquals(1, service.releaseDue())
        assertEquals(0, service.releaseDue())
        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
        assertEquals(1, inviterGrants().size)
        assertEquals(clock.now, store.referralOfInvitee("bob")!!.settledAt)
        assertNull(store.referralOfInvitee("bob")!!.settledBy)
    }

    @Test
    fun `with CLAIM the invitee is paid at once and the inviter waits out the hold`() {
        campaign("qualifyOn" to "CLAIM", "holdDays" to 3)
        invite("alice", "bob")

        assertEquals(ReferralStatus.HELD, status("bob"))
        assertEquals(listOf("bob"), grants.recorded.map { it.first })
    }

    // 같은 네트워크

    @Test
    fun `same network with reviewSameNetwork waits for an operator and the release job never frees it`() {
        campaign("qualifyOn" to "SIGNAL", "reviewSameNetwork" to true, "holdDays" to 1)
        service.me("alice", "net-1")
        invite("alice", "bob", "net-1")
        service.qualify("bob")

        val review = store.referralOfInvitee("bob")!!
        assertEquals(ReferralStatus.REVIEW, review.status)
        assertEquals(setOf(ReferralFlag.SAME_NETWORK), review.flags)
        assertNull(review.releaseAt)
        clock.advance(Duration.ofDays(30))
        assertEquals(0, service.releaseDue())
        assertEquals(ReferralStatus.REVIEW, status("bob"))
        assertEquals(0, inviterGrants().size)
    }

    @Test
    fun `approve pays once, records who, and a second approve is 409`() {
        campaign("qualifyOn" to "SIGNAL", "reviewSameNetwork" to true)
        service.me("alice", "net-1")
        invite("alice", "bob", "net-1")
        service.qualify("bob")

        service.approve("bob", "op_1")

        val done = store.referralOfInvitee("bob")!!
        assertEquals(ReferralStatus.QUALIFIED, done.status)
        assertEquals("op_1", done.settledBy)
        assertEquals(1, inviterGrants().size)
        assertEquals(CampaignErrorCode.REFERRAL_NOT_PENDING, code { service.approve("bob", "op_1") })
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `an operator cannot approve a relation they are part of - the money would go to themselves`() {
        campaign("qualifyOn" to "SIGNAL", "reviewSameNetwork" to true)
        service.me("alice", "net-1")
        invite("alice", "bob", "net-1")
        service.qualify("bob")

        assertEquals(CampaignErrorCode.REFERRAL_SELF_APPROVAL, code { service.approve("bob", "alice") })
        assertEquals(CampaignErrorCode.REFERRAL_SELF_APPROVAL, code { service.approve("bob", "bob") })
        assertEquals(ReferralStatus.REVIEW, status("bob"))
        assertEquals(0, inviterGrants().size)
        service.approve("bob", "op_1")
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `approve skips what is left of a hold`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 30)
        invite("alice", "bob")
        service.qualify("bob")

        service.approve("bob", "op_1")

        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
        assertEquals(1, inviterGrants().size)
        clock.advance(Duration.ofDays(31))
        assertEquals(0, service.releaseDue())
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `approve of a relation that is waiting for nothing or does not exist is refused`() {
        campaign("qualifyOn" to "SIGNAL")
        invite("alice", "bob")
        assertEquals(CampaignErrorCode.REFERRAL_NOT_PENDING, code { service.approve("bob", "op_1") })   // CLAIMED
        assertEquals(CampaignErrorCode.REFERRAL_NOT_FOUND, code { service.approve("nobody", "op_1") })
    }

    @Test
    fun `the same network is only marked when the campaign does not review it`() {
        campaign("qualifyOn" to "SIGNAL")
        service.me("alice", "net-1")
        invite("alice", "bob", "net-1")
        service.qualify("bob")

        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("bob")!!.flags)
    }

    @Test
    fun `a network matches the inviter's last one or a sibling's claim, only within 30 days`() {
        campaign("qualifyOn" to "SIGNAL")
        service.me("alice", "home")
        invite("alice", "bob", "home")          // 초대자의 마지막 네트워크
        invite("alice", "carol", "cafe")        // 다른 곳
        invite("alice", "dave", "cafe")         // 형제 carol 과 같은 곳
        invite("alice", "erin", null)           // 네트워크를 모른다

        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("bob")!!.flags)
        assertEquals(emptySet(), store.referralOfInvitee("carol")!!.flags)
        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("dave")!!.flags)
        assertEquals(emptySet(), store.referralOfInvitee("erin")!!.flags)

        clock.advance(Duration.ofDays(30).plusSeconds(1))
        invite("alice", "frank", "home")
        invite("alice", "gina", "cafe")
        assertEquals(emptySet(), store.referralOfInvitee("frank")!!.flags)
        assertEquals(emptySet(), store.referralOfInvitee("gina")!!.flags)
    }

    @Test
    fun `the release job erases network values older than 30 days and keeps the flag`() {
        campaign("qualifyOn" to "SIGNAL")
        service.me("alice", "home")
        invite("alice", "bob", "home")
        clock.advance(Duration.ofDays(31))

        service.releaseDue()

        assertNull(store.referralOfInvitee("bob")!!.claimNetwork)
        assertTrue(store.codeNetworks.isEmpty())
        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("bob")!!.flags)
    }

    @Test
    fun `one relation that fails to release does not stop the others or the network cleanup`() {
        val failing = object : RewardGrants {
            override fun record(accountId: String, type: String, params: Map<String, String>, sourceType: String, sourceKey: String): String? {
                if (sourceKey.contains(":bob:inviter:")) error("grant store down for bob")
                return grants.record(accountId, type, params, sourceType, sourceKey)
            }
        }
        val svc = ReferralService(store, campaigns, failing, null, clock)
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7)
        svc.me("alice", "home")
        svc.claim("bob", svc.me("alice").code, "home")
        svc.claim("carol", svc.me("alice").code, "home")
        svc.qualify("bob")
        svc.qualify("carol")
        clock.advance(Duration.ofDays(31))

        assertEquals(1, svc.releaseDue())

        assertEquals(1, inviterGrants().size, "carol is paid although bob's release failed")
        assertNull(store.referralOfInvitee("carol")!!.claimNetwork, "the 30 day network cleanup still ran")
        assertTrue(store.codeNetworks.isEmpty())
    }

    // 취소

    @Test
    fun `an app cancel ends a waiting or held relation, and nothing is paid after it`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7)
        invite("alice", "bob"); invite("alice", "carol")
        service.qualify("carol")

        assertTrue(service.cancel("bob", "refunded"))
        assertTrue(service.cancel("carol", "refunded"))

        val canceled = store.referralOfInvitee("carol")!!
        assertEquals(ReferralStatus.CANCELED, canceled.status)
        assertEquals("refunded", canceled.cancelReason)
        assertEquals("app", canceled.settledBy)
        service.qualify("bob")
        clock.advance(Duration.ofDays(8))
        assertEquals(0, service.releaseDue())
        assertEquals(ReferralStatus.CANCELED, status("bob"))
        assertEquals(0, inviterGrants().size)
        assertEquals(CampaignErrorCode.REFERRAL_NOT_PENDING, code { service.approve("carol", "op_1") })
    }

    @Test
    fun `cancel of a finished relation changes nothing - false for the app, 409 for an operator`() {
        campaign("qualifyOn" to "SIGNAL")
        invite("alice", "bob")
        service.qualify("bob")

        assertFalse(service.cancel("bob", "refunded"))
        assertFalse(service.cancel("nobody", "refunded"))
        assertEquals(CampaignErrorCode.REFERRAL_NOT_CANCELABLE, code { service.cancelByOperator("bob", "fraud", "op_1") })
        assertEquals(CampaignErrorCode.REFERRAL_NOT_FOUND, code { service.cancelByOperator("nobody", "fraud", "op_1") })
        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
    }

    @Test
    fun `an operator cancel records who and why, and the reason is required`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7)
        invite("alice", "bob")
        service.qualify("bob")

        assertEquals(CampaignErrorCode.REFERRAL_REASON_INVALID, code { service.cancelByOperator("bob", "  ", "op_1") })
        assertEquals(CampaignErrorCode.REFERRAL_REASON_INVALID, code { service.cancelByOperator("bob", "x".repeat(201), "op_1") })
        service.cancelByOperator("bob", "farming", "op_1")

        val r = store.referralOfInvitee("bob")!!
        assertEquals(ReferralStatus.CANCELED, r.status)
        assertEquals("op_1", r.settledBy)
        assertEquals("farming", r.cancelReason)
    }

    // 상한

    @Test
    fun `held and review relations take a cap slot, so a release never passes the cap`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7, "inviterCap" to 1)
        invite("alice", "bob"); invite("alice", "carol")

        service.qualify("bob")
        service.qualify("carol")
        assertEquals(ReferralStatus.HELD, status("bob"))
        assertEquals(ReferralStatus.CAPPED, status("carol"))

        clock.advance(Duration.ofDays(7))
        service.releaseDue()
        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `a canceled relation gives its cap slot back`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7, "inviterCap" to 1)
        invite("alice", "bob"); invite("alice", "carol")
        service.qualify("bob")
        service.cancel("bob", "refunded")

        service.qualify("carol")

        assertEquals(ReferralStatus.HELD, status("carol"))
    }

    // 탈퇴

    @Test
    fun `a held relation whose inviter left is closed on release without any grant`() {
        campaign("qualifyOn" to "SIGNAL", "holdDays" to 7)
        invite("alice", "bob")
        service.qualify("bob")
        store.eraseAccount("alice", "deleted:x")
        clock.advance(Duration.ofDays(7))

        assertEquals(0, service.releaseDue())

        assertEquals(ReferralStatus.CANCELED, status("bob"))
        assertEquals(0, inviterGrants().size)
    }
}
