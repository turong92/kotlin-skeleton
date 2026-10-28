package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.CampaignErrorCode
import dev.sumin.skeleton.campaign.CampaignException
import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.campaign.ReferralService
import dev.sumin.skeleton.campaign.Referrals
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 친구초대 - 남용 거절(자기 · 맞초대 · 이미 초대받음 · 가입 7일 경과) · 양쪽 보상 시점 · 상한이 두 방언에서 같다 */
@Import(DbTestcontainers::class, CampaignDbFixtures::class)
@SpringBootTest(classes = [CampaignJdbcTestApplication::class])
class ReferralDbTest {
    @Autowired lateinit var campaigns: Campaigns
    @Autowired lateinit var referrals: ReferralService
    @Autowired lateinit var signal: Referrals
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var grants: RecordingGrants
    @Autowired lateinit var facts: FakeFacts
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var store: dev.sumin.skeleton.campaign.CampaignStore

    private val now = Instant.parse("2026-10-28T00:00:00Z")
    private fun reward(amount: String) = listOf(mapOf("type" to "point", "params" to mapOf("amount" to amount)))

    @BeforeEach
    fun clean() {
        listOf("campaign_attendance", "campaigns", "campaign_referral_codes", "campaign_referrals").forEach { jdbc.sql("delete from $it").update() }
        grants.clear()
        time.now = now
        facts.createdAt.clear()
    }

    private fun referralCampaign(qualifyOn: String, cap: Int? = null): String =
        campaigns.create(
            "referral",
            CampaignInput("Invite", Instant.parse("2026-01-01T00:00:00Z"), null, mapOf("invitee" to reward("30"), "inviter" to reward("50"), "qualifyOn" to qualifyOn, "inviterCap" to cap)),
        ).id

    private fun newAccount(id: String, ageDays: Long = 0) {
        facts.createdAt[id] = now.minusSeconds(ageDays * 86_400)
    }

    private fun reasonOf(block: () -> Unit): String? {
        val e = assertFailsWith<CampaignException> { block() }
        assertEquals(CampaignErrorCode.REFERRAL_INVALID, e.errorCode)
        return (e.data as Map<*, *>)["reason"] as String?
    }

    @Test
    fun `own code, second claim, mutual invite and an account older than 7 days are refused with a reason`() {
        referralCampaign("CLAIM")
        listOf("alice", "bob", "carol", "old").forEach { newAccount(it) }
        newAccount("old", ageDays = 8)
        val aliceCode = referrals.me("alice").code

        assertEquals("OWN_CODE", reasonOf { referrals.claim("alice", aliceCode) })
        assertEquals("UNKNOWN_CODE", reasonOf { referrals.claim("bob", "ZZZZZZZZ") })
        referrals.claim("bob", aliceCode)
        assertEquals("ALREADY_INVITED", reasonOf { referrals.claim("bob", referrals.me("carol").code) })
        assertEquals("MUTUAL", reasonOf { referrals.claim("alice", referrals.me("bob").code) })
        assertEquals("NOT_NEW_ACCOUNT", reasonOf { referrals.claim("old", aliceCode) })
    }

    @Test
    fun `7 days exactly is still new`() {
        referralCampaign("CLAIM")
        newAccount("alice")
        newAccount("bob", ageDays = 7)
        referrals.claim("bob", referrals.me("alice").code)
        assertEquals(2, grants.recorded.size)
    }

    @Test
    fun `without an active referral campaign a claim is 409 NOT_ACTIVE`() {
        newAccount("bob")
        val e = assertFailsWith<CampaignException> { referrals.claim("bob", "ANYCODE1") }
        assertEquals(CampaignErrorCode.NOT_ACTIVE, e.errorCode)
    }

    @Test
    fun `CLAIM pays both sides at once`() {
        val id = referralCampaign("CLAIM")
        newAccount("alice"); newAccount("bob")
        referrals.claim("bob", referrals.me("alice").code)

        assertEquals(setOf("bob" to "$id:bob:invitee:0", "alice" to "$id:bob:inviter:0"), grants.recorded.map { it.accountId to it.sourceKey }.toSet())
        val me = referrals.me("alice")
        assertEquals(1, me.invited)
        assertEquals(1, me.rewarded)
    }

    @Test
    fun `SIGNAL pays the invitee now and the inviter once after qualify, however often it is called`() {
        val id = referralCampaign("SIGNAL")
        newAccount("alice"); newAccount("bob")
        referrals.claim("bob", referrals.me("alice").code)
        assertEquals(listOf("bob"), grants.recorded.map { it.accountId })
        assertEquals(0, referrals.me("alice").rewarded)

        signal.qualify("bob")
        signal.qualify("bob")
        signal.qualify("nobody")

        assertEquals(listOf("bob", "alice"), grants.recorded.map { it.accountId })
        assertEquals("$id:bob:inviter:0", grants.recorded.last().sourceKey)
        assertEquals(1, referrals.me("alice").rewarded)
    }

    @Test
    fun `an inviter cap of 2 keeps the third invite as a relation with no grants`() {
        referralCampaign("CLAIM", cap = 2)
        newAccount("alice")
        val code = referrals.me("alice").code
        listOf("b1", "b2", "b3").forEach { newAccount(it); referrals.claim(it, code) }

        assertEquals(4, grants.recorded.size)   // 둘 x (초대받은 쪽 + 초대자)
        assertEquals(emptyList(), grants.recorded.filter { it.sourceKey.contains(":b3:") })
        val me = referrals.me("alice")
        assertEquals(3, me.invited)
        assertEquals(2, me.rewarded)
    }

    @Test
    fun `the invite code is stable per account`() {
        newAccount("alice")
        assertEquals(referrals.me("alice").code, referrals.me("alice").code)
        assertEquals(8, referrals.me("alice").code.length)
    }

    /** 모든 스레드가 같은 순간에 출발해 각자 결과(예외 이름 또는 "ok")를 돌려준다 */
    private fun <T> race(n: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val futures = (0 until n).map { i -> pool.submit<Result<T>> { start.await(); runCatching { task(i) } } }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        return results
    }

    @Test
    fun `six invitees claiming one code at once cannot pass the inviter cap of 2`() {
        referralCampaign("CLAIM", cap = 2)
        newAccount("alice")
        val code = referrals.me("alice").code
        (1..6).forEach { newAccount("b$it") }

        val results = race(6) { referrals.claim("b${it + 1}", code) }

        assertEquals(6, results.count { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(2, store.countReferrals("alice", null, dev.sumin.skeleton.campaign.ReferralStatus.QUALIFIED))
        assertEquals(4, store.countReferrals("alice", null, dev.sumin.skeleton.campaign.ReferralStatus.CAPPED))
        assertEquals(4, grants.recorded.size)   // 상한 안의 둘 x (초대받은 쪽 + 초대자)
    }

    @Test
    fun `qualify called at once for six invitees cannot pass the cap either`() {
        referralCampaign("SIGNAL", cap = 2)
        newAccount("alice")
        val code = referrals.me("alice").code
        (1..6).forEach { newAccount("b$it"); referrals.claim("b$it", code) }

        val results = race(6) { signal.qualify("b${it + 1}") }

        assertEquals(6, results.count { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(2, store.countReferrals("alice", null, dev.sumin.skeleton.campaign.ReferralStatus.QUALIFIED))
        assertEquals(2, grants.recorded.count { it.accountId == "alice" })
    }

    @Test
    fun `A and B claiming each other's code at once make exactly one relation`() {
        referralCampaign("CLAIM")
        newAccount("alice"); newAccount("bob")
        val aliceCode = referrals.me("alice").code
        val bobCode = referrals.me("bob").code

        val results = race(2) { if (it == 0) referrals.claim("alice", bobCode) else referrals.claim("bob", aliceCode) }

        assertEquals(1, results.count { it.isSuccess }, results.mapNotNull { it.exceptionOrNull() }.toString())
        assertEquals(1L, jdbc.sql("select count(*) from campaign_referrals").query(Long::class.java).single())
    }

    @Test
    fun `erasing the inviter keeps the relation, hides the inviter and keeps the invitee's once-only claim`() {
        referralCampaign("SIGNAL")
        newAccount("alice"); newAccount("bob"); newAccount("carol")
        referrals.claim("bob", referrals.me("alice").code)

        store.eraseAccount("alice", "deleted:abc")

        assertEquals("deleted:abc", store.referralOfInvitee("bob")!!.inviterId)
        assertEquals("ALREADY_INVITED", reasonOf { referrals.claim("bob", referrals.me("carol").code) })
        signal.qualify("bob")   // 지워진 초대자에게는 보상이 나가지 않는다
        assertEquals(listOf("bob"), grants.recorded.map { it.accountId })

        store.eraseAccount("bob", "deleted:def")   // 초대받은 쪽이 지워지면 줄도 지워진다
        assertEquals(null, store.referralOfInvitee("bob"))
    }
}
