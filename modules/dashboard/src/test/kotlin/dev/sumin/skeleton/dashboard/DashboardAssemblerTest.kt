package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardCohortMember
import dev.sumin.skeleton.common.dashboard.DashboardCohortSource
import dev.sumin.skeleton.common.dashboard.DashboardFunnel
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import dev.sumin.skeleton.dashboardtest.FakeMetric
import dev.sumin.skeleton.dashboardtest.ok
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** 돈 모양: 그래프 통화는 타일 첫 통화 하나(이번 · 직전 같이), 매출 0 은 통화가 있는 0 한 줄, 퍼널 묶음 상한 */
class DashboardAssemblerTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val period = DashboardRangeParser(zone) { Instant.parse("2026-10-26T03:00:00Z") }.parse("2026-10-20", "2026-10-26")

    private fun assembler(vararg metrics: FakeMetric, currency: String = "", funnel: DashboardFunnel? = null, maxCohort: Int = 50_000) =
        DashboardAssembler({ metrics.toList() }, { emptyList() }, { funnel }, DashboardCache(Duration.ZERO), { currency }, Duration.ZERO, maxCohort)

    @Test
    fun `the series uses the first currency of the tile for this and the previous period`() {
        val krwOnlyBefore = FakeMetric("m", unit = DashboardUnit.MONEY) { range ->
            // 이번 기간: 환불만 있는 KRW(−7000)와 USD 990 / 직전 기간: USD 만
            if (range.from == period.range.from) ok(DashboardAmount(990, "USD"), DashboardAmount(-7000, "KRW")) else ok(DashboardAmount(500, "USD"))
        }
        val response = assembler(krwOnlyBefore).overview(period, compare = true).metrics.single()

        assertEquals(listOf("KRW", "USD"), response.value.map { it.currency })
        assertEquals(listOf("KRW", "KRW"), krwOnlyBefore.seriesCurrencies.toList())   // 이번 · 직전 모두 타일 첫 통화
    }

    @Test
    fun `a period without money is a zero line in the configured currency, else the previous currency`() {
        val quiet = FakeMetric("m", unit = DashboardUnit.MONEY) { range -> if (range.from == period.range.from) ok() else ok(DashboardAmount(500, "USD")) }

        assertEquals(listOf(DashboardAmount(0, "KRW")), assembler(quiet, currency = "KRW").overview(period, compare = false).metrics.single().value)
        assertEquals(listOf(DashboardAmount(0, "USD")), assembler(quiet).overview(period, compare = false).metrics.single().value)
        val compared = assembler(quiet, currency = "KRW").overview(period, compare = true).metrics.single()
        assertEquals(listOf(DashboardAmount(500, "USD")), compared.previousValue)
    }

    @Test
    fun `a cohort over the cap makes only the funnel say too large`() {
        val cohort = object : DashboardCohortSource {
            override val key = "c"
            override val label = "C"
            override fun members(range: DashboardRange, limit: Int) = (1..limit + 1).map { DashboardCohortMember("acc_$it", Instant.EPOCH) }
        }
        val response = assembler(FakeMetric("m") { ok(DashboardAmount(1)) }, funnel = DashboardFunnel(cohort, emptyList()), maxCohort = 3).overview(period, compare = false)

        assertEquals("TOO_LARGE", response.funnel!!.status)
        assertEquals(emptyList(), response.funnel!!.steps)
        assertEquals("OK", response.metrics.single().status.name)
    }
}
