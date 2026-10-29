package dev.sumin.skeleton.campaign.jdbc

import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.CampaignSignals
import dev.sumin.skeleton.campaign.CampaignStore
import dev.sumin.skeleton.campaign.Campaigns
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 업적 수치는 두 방언(postgresTest · mysqlTest)에서 동시 보고에도 큰 값만 남고, 단계 보상은 한 번씩만 기록된다 */
@Import(DbTestcontainers::class, CampaignDbFixtures::class)
@SpringBootTest(classes = [CampaignJdbcTestApplication::class])
class AchievementDbTest {
    @Autowired lateinit var store: CampaignStore
    @Autowired lateinit var campaigns: Campaigns
    @Autowired lateinit var signals: CampaignSignals
    @Autowired lateinit var grants: RecordingGrants
    @Autowired lateinit var jdbc: JdbcClient

    private fun steps() = mapOf(
        "metric" to "notes.created",
        "milestones" to listOf(
            mapOf("value" to 1, "rewards" to listOf(mapOf("type" to "point", "params" to mapOf("amount" to "10")))),
            mapOf("value" to 10, "rewards" to listOf(mapOf("type" to "point", "params" to mapOf("amount" to "100")))),
        ),
    )

    @BeforeEach
    fun clean() {
        listOf("campaign_progress", "campaigns").forEach { jdbc.sql("delete from $it").update() }
        grants.clear()
        campaigns.create("achievement", CampaignInput("Notes", Instant.parse("2026-01-01T00:00:00Z"), null, steps()))
    }

    private fun <T> concurrently(count: Int, block: (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        val futures = (0 until count).map { i -> pool.submit<T> { start.await(); block(i) } }
        start.countDown()
        return futures.map { it.get(60, TimeUnit.SECONDS) }.also { pool.shutdown() }
    }

    @Test
    fun `concurrent reports keep the biggest value and record each step once`() {
        concurrently(8) { signals.report("acc_1", "notes.created", (it + 1).toLong() * 2) }   // 2..16
        assertEquals(16L, store.progress("acc_1", "notes.created"))
        assertEquals(2, grants.recorded.size)   // 1 단계와 10 단계 한 번씩
    }

    @Test
    fun `a lower report leaves the value and the same value again adds nothing`() {
        signals.report("acc_1", "notes.created", 10)
        signals.report("acc_1", "notes.created", 3)
        signals.report("acc_1", "notes.created", 10)
        assertEquals(10L, store.progress("acc_1", "notes.created"))
        assertEquals(2, grants.recorded.size)
    }

    @Test
    fun `erasing an account deletes its progress`() {
        signals.report("acc_1", "notes.created", 4)
        store.eraseAccount("acc_1", "deleted:x")
        assertEquals(null, store.progress("acc_1", "notes.created"))
    }
}
