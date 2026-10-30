package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.campaign.ReferralFlag
import dev.sumin.skeleton.campaign.ReferralService
import dev.sumin.skeleton.campaign.ReferralStatus
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 보류 · 확인 · 취소 · 같은 네트워크 표시가 두 방언에서 같다 - 상한 · 해제 · 승인이 겹쳐도 지급 건은 한 벌 */
@Import(DbTestcontainers::class, CampaignDbFixtures::class)
@SpringBootTest(classes = [CampaignJdbcTestApplication::class])
class ReferralReviewDbTest {
    @Autowired lateinit var campaigns: Campaigns
    @Autowired lateinit var referrals: ReferralService
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var grants: RecordingGrants
    @Autowired lateinit var facts: FakeFacts
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var store: CampaignStore

    private val start = Instant.parse("2026-10-30T00:00:00Z")
    private fun reward(amount: String) = listOf(mapOf("type" to "point", "params" to mapOf("amount" to amount)))

    @BeforeEach
    fun clean() {
        listOf("campaign_attendance", "campaigns", "campaign_referral_codes", "campaign_referrals").forEach { jdbc.sql("delete from $it").update() }
        grants.clear()
        time.now = start
        facts.createdAt.clear()
    }

    private fun campaign(vararg extra: Pair<String, Any?>): String =
        campaigns.create(
            "referral",
            CampaignInput("Invite", Instant.parse("2026-01-01T00:00:00Z"), null, mapOf("invitee" to reward("30"), "inviter" to reward("50"), "qualifyOn" to "SIGNAL") + extra),
        ).id

    private fun account(id: String) { facts.createdAt[id] = time.now }
    private fun invite(inviter: String, invitee: String, network: String? = null) {
        account(inviter); account(invitee)
        referrals.claim(invitee, referrals.me(inviter).code, network)
    }
    private fun inviterGrants() = grants.recorded.filter { it.sourceKey.contains(":inviter:") }
    private fun status(invitee: String) = store.referralOfInvitee(invitee)!!.status

    private fun <T> race(n: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        val go = CountDownLatch(1)
        val futures = (0 until n).map { i -> pool.submit<Result<T>> { go.await(); runCatching { task(i) } } }
        go.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        return results
    }

    @Test
    fun `a signal holds the reward until the date, and releasing twice pays once`() {
        campaign("holdDays" to 7, "qualifyEvent" to "first-payment")
        invite("alice", "bob")

        referrals.qualify("bob", "other")
        assertEquals(ReferralStatus.CLAIMED, status("bob"))
        referrals.qualify("bob", "first-payment")

        val held = store.referralOfInvitee("bob")!!
        assertEquals(ReferralStatus.HELD, held.status)
        assertEquals(start.plus(Duration.ofDays(7)), held.releaseAt)
        assertEquals("first-payment", held.qualifyEvent)
        assertEquals(0, inviterGrants().size)

        time.now = start.plus(Duration.ofDays(7))
        assertEquals(1, referrals.releaseDue())
        assertEquals(0, referrals.releaseDue())
        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
        assertEquals(time.now, store.referralOfInvitee("bob")!!.settledAt)
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `the release job run at once by two instances pays one set`() {
        campaign("holdDays" to 1)
        invite("alice", "bob")
        referrals.qualify("bob")
        time.now = start.plus(Duration.ofDays(2))

        val results = race(4) { referrals.releaseDue() }

        assertEquals(4, results.count { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(1, results.sumOf { it.getOrThrow() })
        assertEquals(1, inviterGrants().size)
    }

    @Test
    fun `an operator approval racing the release pays one set and exactly one of them wins`() {
        campaign("holdDays" to 1)
        invite("alice", "bob")
        referrals.qualify("bob")
        time.now = start.plus(Duration.ofDays(2))

        val results = race(2) { if (it == 0) referrals.approve("bob", "op_1") else referrals.releaseDue() }

        assertEquals(1, inviterGrants().size, results.toString())
        assertEquals(ReferralStatus.QUALIFIED, status("bob"))
    }

    @Test
    fun `held and review relations take cap slots, so six signals at once fill exactly the cap`() {
        campaign("holdDays" to 7, "inviterCap" to 2)
        account("alice")
        val code = referrals.me("alice").code
        (1..6).forEach { account("b$it"); referrals.claim("b$it", code) }

        val results = race(6) { referrals.qualify("b${it + 1}") }

        assertEquals(6, results.count { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(2, store.countReferralsIn("alice", null, setOf(ReferralStatus.HELD)))
        assertEquals(4, store.countReferralsIn("alice", null, setOf(ReferralStatus.CAPPED)))
        time.now = start.plus(Duration.ofDays(8))
        referrals.releaseDue()
        assertEquals(2, inviterGrants().size)
        assertEquals(2, store.countReferralsIn("alice", null, setOf(ReferralStatus.QUALIFIED)))
    }

    @Test
    fun `same network is read from the inviter's last network and from siblings, only within 30 days, and the job erases the values but not the flag`() {
        campaign("reviewSameNetwork" to true)
        account("alice")
        referrals.me("alice", "net-home")
        invite("alice", "bob", "net-home")
        invite("alice", "carol", "net-cafe")
        invite("alice", "dave", "net-cafe")
        referrals.qualify("bob")

        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("bob")!!.flags)
        assertEquals(emptySet(), store.referralOfInvitee("carol")!!.flags)
        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("dave")!!.flags)
        assertEquals(ReferralStatus.REVIEW, status("bob"))

        time.now = start.plus(Duration.ofDays(30)).plusSeconds(1)
        invite("alice", "erin", "net-home")
        invite("alice", "frank", "net-cafe")
        assertEquals(emptySet(), store.referralOfInvitee("erin")!!.flags)
        assertEquals(emptySet(), store.referralOfInvitee("frank")!!.flags)

        referrals.releaseDue()
        assertNull(store.referralOfInvitee("bob")!!.claimNetwork)
        assertEquals(0L, jdbc.sql("select count(*) from campaign_referral_codes where account_id = 'alice' and last_network is not null").query(Long::class.java).single())
        assertEquals(setOf(ReferralFlag.SAME_NETWORK), store.referralOfInvitee("bob")!!.flags)
        assertEquals("net-cafe", store.referralOfInvitee("frank")!!.claimNetwork)   // 방금 한 claim 은 30일 안이라 남는다
    }

    @Test
    fun `a relation written before the new columns reads with empty values`() {
        campaign()
        jdbc.sql("insert into campaign_referrals (invitee_id, inviter_id, campaign_id, status, claimed_at) values ('old_b', 'old_a', 'cmp', 'QUALIFIED', :at)")
            .param("at", java.sql.Timestamp.from(start)).update()

        val r = store.referralOfInvitee("old_b")!!

        assertEquals(ReferralStatus.QUALIFIED, r.status)
        assertEquals(emptySet(), r.flags)
        assertNull(r.releaseAt); assertNull(r.settledAt); assertNull(r.claimNetwork); assertNull(r.qualifyEvent)
    }

    @Test
    fun `erasing the invitee removes the relation with its network, erasing the inviter removes the code with its last network`() {
        campaign()
        account("alice")
        referrals.me("alice", "n")
        invite("alice", "bob", "n")

        store.eraseAccount("bob", "deleted:b")
        assertNull(store.referralOfInvitee("bob"))

        store.eraseAccount("alice", "deleted:a")
        assertEquals(0L, jdbc.sql("select count(*) from campaign_referral_codes where account_id = 'alice'").query(Long::class.java).single())
    }
}
