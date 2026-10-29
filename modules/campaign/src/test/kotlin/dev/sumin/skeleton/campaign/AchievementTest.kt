package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AchievementTest {
    private val store = FakeCampaignStore()
    private val grants = RecordingGrants()
    private val time = TimeProvider.fixed(Instant.parse("2026-10-29T01:00:00Z"))
    private val signals = AchievementService(store, grants, time)
    private val kind = AchievementKind()
    private val issuers = mapOf("point" to PointIssuer())

    private fun reward(type: String = "point", amount: String = "10") = mapOf("type" to type, "params" to mapOf("amount" to amount))
    private fun step(value: Int, vararg rewards: Map<String, Any?>) = mapOf("value" to value, "rewards" to rewards.toList())
    private fun config(metric: String = "notes.created", vararg steps: Map<String, Any?>) = mapOf("metric" to metric, "milestones" to steps.toList())

    private fun campaign(id: String = "cmp_1", config: Map<String, Any?>, enabled: Boolean = true, startsAt: String = "2026-01-01T00:00:00Z", endsAt: String? = null) {
        store.insert(Campaign(id, "achievement", "Notes", enabled, Instant.parse(startsAt), endsAt?.let(Instant::parse), config, time.now(), time.now()))
    }

    @BeforeTest
    fun reset() {
        store.progress.clear()
        store.campaigns.clear()
        grants.recorded.clear()
    }

    @Test
    fun `a valid config parses with its steps in order`() {
        val parsed = AchievementConfig.parse(config("notes.created", step(1, reward()), step(10, reward(), reward(amount = "20"))))
        assertEquals("notes.created", parsed.metric)
        assertEquals(listOf(1L, 10L), parsed.milestones.map { it.value })
        assertEquals(2, parsed.milestones[1].rewards.size)
    }

    @Test
    fun `a bad config is CONFIG_INVALID - metric shape, step count, order, duplicates, empty rewards`() {
        fun bad(c: Map<String, Any?>) = assertFailsWith<CampaignException> { kind.validate(c, issuers) }.also { assertEquals("CAMPAIGN.CONFIG_INVALID", it.errorCode.code) }
        bad(config("Bad Metric", step(1, reward())))
        bad(config("", step(1, reward())))
        bad(config("x".repeat(65), step(1, reward())))
        bad(mapOf("milestones" to listOf(step(1, reward()))))
        bad(config("m"))
        bad(config("m", *(1..21).map { step(it, reward()) }.toTypedArray()))
        bad(config("m", step(10, reward()), step(1, reward())))
        bad(config("m", step(5, reward()), step(5, reward())))
        bad(config("m", step(0, reward())))
        bad(config("m", step(1)))
        bad(config("m", step(1, reward(type = "badge"))))   // 모르는 보상 종류
        kind.validate(config("a-b_c.1", *(1..20).map { step(it, reward()) }.toTypedArray()), issuers)
    }

    @Test
    fun `reporting 9 then 10 grants the 10 step once, and the same 10 again adds nothing`() {
        campaign(config = config("notes.created", step(1, reward()), step(10, reward())))
        signals.report("acc_1", "notes.created", 9)
        assertEquals(1, grants.recorded.size)
        signals.report("acc_1", "notes.created", 10)
        assertEquals(2, grants.recorded.size)
        signals.report("acc_1", "notes.created", 10)
        assertEquals(2, grants.recorded.size)
        assertEquals(setOf("achievement/cmp_1:acc_1:achv:1:0", "achievement/cmp_1:acc_1:achv:10:0"), grants.recorded.map { it.third }.toSet())
    }

    @Test
    fun `a lower value is ignored and the stored value only grows`() {
        campaign(config = config("notes.created", step(10, reward())))
        signals.report("acc_1", "notes.created", 10)
        signals.report("acc_1", "notes.created", 5)
        assertEquals(10L, store.progress("acc_1", "notes.created"))
        assertEquals(1, grants.recorded.size)
    }

    @Test
    fun `every reward of a step is recorded under its own index`() {
        campaign(config = config("notes.created", step(10, reward(), reward(amount = "100"))))
        signals.report("acc_1", "notes.created", 10)
        assertEquals(listOf("achievement/cmp_1:acc_1:achv:10:0", "achievement/cmp_1:acc_1:achv:10:1"), grants.recorded.map { it.third })
    }

    @Test
    fun `a campaign made later pays the steps already passed on the next report`() {
        signals.report("acc_1", "notes.created", 12)
        assertEquals(0, grants.recorded.size)
        campaign(config = config("notes.created", step(1, reward()), step(10, reward()), step(20, reward())))
        signals.report("acc_1", "notes.created", 12)
        assertEquals(2, grants.recorded.size)
    }

    @Test
    fun `a disabled, not yet started, ended or other-metric campaign grants nothing, but the value is still kept`() {
        campaign("cmp_off", config("notes.created", step(1, reward())), enabled = false)
        campaign("cmp_future", config("notes.created", step(1, reward())), startsAt = "2027-01-01T00:00:00Z")
        campaign("cmp_past", config("notes.created", step(1, reward())), endsAt = "2026-02-01T00:00:00Z")
        campaign("cmp_other", config("posts.created", step(1, reward())))
        store.insert(Campaign("cmp_att", "attendance", "A", true, Instant.parse("2026-01-01T00:00:00Z"), null, mapOf("daily" to listOf(reward())), time.now(), time.now()))
        signals.report("acc_1", "notes.created", 3)
        assertEquals(0, grants.recorded.size)
        assertEquals(3L, store.progress("acc_1", "notes.created"))
    }

    @Test
    fun `two accounts and two campaigns on one metric are separate`() {
        campaign("cmp_1", config("m", step(1, reward())))
        campaign("cmp_2", config("m", step(1, reward())))
        signals.report("acc_1", "m", 1)
        signals.report("acc_2", "m", 1)
        assertEquals(4, grants.recorded.size)
    }

    @Test
    fun `a bad metric or a negative value is CONFIG_INVALID`() {
        assertFailsWith<CampaignException> { signals.report("acc_1", "Bad Metric", 1) }
        assertFailsWith<CampaignException> { signals.report("acc_1", "m", -1) }
    }

    @Test
    fun `erasing an account removes its progress`() {
        signals.report("acc_1", "m", 4)
        store.eraseAccount("acc_1", "deleted:x")
        assertEquals(null, store.progress("acc_1", "m"))
    }
}
