package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.AttendanceService
import dev.sumin.skeleton.campaign.CampaignErrorCode
import dev.sumin.skeleton.campaign.CampaignException
import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.CampaignZone
import dev.sumin.skeleton.campaign.Campaigns
import dev.sumin.skeleton.common.reward.RewardIssuer
import java.time.Instant
import java.time.ZoneId
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

/** 출석 - 서비스 시간대 기준일 · 하루 한 번 · 달성 보상이 두 방언(postgresTest · mysqlTest)에서 같다. 지급 기록은 대역이라 쿠폰 · 포인트 표를 만지지 않는다. */
@Import(DbTestcontainers::class, CampaignDbFixtures::class)
@SpringBootTest(classes = [CampaignJdbcTestApplication::class], properties = ["skeleton.campaign.zone=Asia/Seoul"])
class AttendanceDbTest {
    @Autowired lateinit var store: CampaignStore
    @Autowired lateinit var campaigns: Campaigns
    @Autowired lateinit var attendance: AttendanceService
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var grants: RecordingGrants
    @Autowired lateinit var issuers: List<RewardIssuer>
    @Autowired lateinit var jdbc: JdbcClient

    private val daily = listOf(mapOf("type" to "point", "params" to mapOf("amount" to "10")))

    @BeforeEach
    fun clean() {
        listOf("campaign_attendance", "campaigns", "campaign_referral_codes", "campaign_referrals").forEach { jdbc.sql("delete from $it").update() }
        grants.clear()
    }

    private fun campaign(config: Map<String, Any?> = mapOf("daily" to daily), enabled: Boolean = true, endsAt: Instant? = null): String {
        val created = campaigns.create("attendance", CampaignInput("Daily", Instant.parse("2026-01-01T00:00:00Z"), endsAt, config))
        if (!enabled) campaigns.setEnabled(created.id, false)
        return created.id
    }

    private fun at(iso: String) {
        time.now = Instant.parse(iso)
    }

    @Test
    fun `23_59_59 and 00_00_00 in the service zone are different days`() {
        val id = campaign()
        at("2026-10-28T14:59:59Z")   // Asia/Seoul 10-28 23:59:59
        val first = attendance.checkIn("acc_1", id)
        at("2026-10-28T15:00:00Z")   // Asia/Seoul 10-29 00:00:00
        val second = attendance.checkIn("acc_1", id)

        assertEquals(false, first.alreadyChecked)
        assertEquals(false, second.alreadyChecked)
        assertEquals(2, second.total)
        assertEquals(2, second.streak)
        assertEquals(2, grants.recorded.size)
        assertEquals("$id:acc_1:2026-10-28:daily:0", grants.recorded[0].sourceKey)
        assertEquals("$id:acc_1:2026-10-29:daily:0", grants.recorded[1].sourceKey)
    }

    @Test
    fun `the same day twice pays once and the second answer says already checked`() {
        val id = campaign()
        at("2026-10-28T01:00:00Z")
        attendance.checkIn("acc_1", id)
        at("2026-10-28T10:00:00Z")   // 같은 서비스 날짜
        val again = attendance.checkIn("acc_1", id)

        assertEquals(true, again.alreadyChecked)
        assertEquals(emptyList(), again.rewards)
        assertEquals(1, grants.recorded.size)
    }

    @Test
    fun `many requests at the same moment make one attendance row and one grant`() {
        val id = campaign()
        at("2026-10-28T01:00:00Z")
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (1..8).map { pool.submit<Boolean> { start.await(); attendance.checkIn("acc_1", id).alreadyChecked } }
        start.countDown()
        val already = results.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, already.count { !it })
        assertEquals(1L, jdbc.sql("select count(*) from campaign_attendance").query(Long::class.java).single())
        assertEquals(1, grants.recorded.size)
    }

    @Test
    fun `a total milestone pays once on its day and not on the next`() {
        val id = campaign(mapOf("daily" to daily, "milestones" to listOf(mapOf("days" to 3, "streak" to false, "rewards" to listOf(mapOf("type" to "point", "params" to mapOf("amount" to "100")))))))
        val lines = (1..4).map { day ->
            at("2026-10-%02dT01:00:00Z".format(27 + day))   // 10-28 .. 10-31 (Seoul same dates at 10:00)
            attendance.checkIn("acc_1", id).rewards.size
        }

        assertEquals(listOf(1, 1, 2, 1), lines)
        assertEquals(1, grants.recorded.count { it.params["amount"] == "100" })
    }

    @Test
    fun `a broken streak counts again and earns the streak milestone again`() {
        val id = campaign(mapOf("milestones" to listOf(mapOf("days" to 2, "streak" to true, "rewards" to listOf(mapOf("type" to "point", "params" to mapOf("amount" to "50")))))))
        val streaks = listOf("2026-10-01", "2026-10-02", "2026-10-04", "2026-10-05").map {
            at("${it}T01:00:00Z")
            attendance.checkIn("acc_1", id).streak
        }

        assertEquals(listOf(1, 2, 1, 2), streaks)
        assertEquals(2, grants.recorded.count { it.params["amount"] == "50" })
    }

    @Test
    fun `New York keeps one day for the whole 25 hours of the fall-back date`() {
        val ny = AttendanceService(store, grants, { issuers }, CampaignZone(ZoneId.of("America/New_York")), time)
        val id = campaign()
        val results = listOf("2026-11-01T04:00:00Z", "2026-11-02T04:59:00Z", "2026-11-02T05:00:00Z").map {   // 00:00 EDT, 23:59 EST (같은 날), 00:00 EST (다음 날)
            at(it)
            ny.checkIn("acc_1", id).alreadyChecked
        }

        assertEquals(listOf(false, true, false), results)
    }

    @Test
    fun `a disabled or ended campaign refuses with 409 NOT_ACTIVE`() {
        at("2026-10-28T01:00:00Z")
        val disabled = campaign(enabled = false)
        val ended = campaign(endsAt = Instant.parse("2026-10-28T00:30:00Z"))

        listOf(disabled, ended).forEach {
            val e = assertFailsWith<CampaignException> { attendance.checkIn("acc_1", it) }
            assertEquals(CampaignErrorCode.NOT_ACTIVE, e.errorCode)
        }
        assertEquals(0, grants.recorded.size)
    }
}
