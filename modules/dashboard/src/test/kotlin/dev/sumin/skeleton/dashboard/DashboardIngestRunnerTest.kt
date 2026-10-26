package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

/** 적재 작업: 어제부터 거슬러 backfill-days 일, 다시 돌려도 같은 표, 적재기 하나가 던져도 다른 적재기는 돈다, 프로젝트마다 */
class DashboardIngestRunnerTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-10-26T20:00:00Z")   // 서울 10-27 05:00 → 어제 = 10-26

    /** 키가 (day, source, kind, dimension) 인 메모리 표 — 저장소 포트의 upsert 규칙(같은 키는 덮어쓴다) 그대로 */
    private class MemoryStore : DashboardDailyStore {
        val rows = linkedMapOf<List<Any>, DashboardDailyRow>()
        val projects = mutableListOf<String?>()
        override fun upsert(rows: List<DashboardDailyRow>) {
            projects += ProjectContext.currentOrNull()?.id
            rows.forEach { this.rows[listOf(it.day, it.source, it.kind, it.dimension)] = it }
        }
        override fun read(source: String, kind: String, fromDay: LocalDate, toDay: LocalDate) = rows.values.filter { it.source == source && it.kind == kind && it.day in fromDay..toDay }
        override fun lastIngested(source: String): Instant? = null
    }

    private class Constant(override val source: String, private val value: Long = 7) : DashboardIngestor {
        override fun ingest(day: LocalDate, zone: ZoneId) = listOf(DashboardDailyRow(day, source, "visits", "", value))
    }

    private fun runner(store: MemoryStore, ingestors: List<DashboardIngestor>, backfill: Int = 3, projects: ProjectRegistry? = null, failures: MutableList<String> = mutableListOf()) =
        DashboardIngestRunner({ ingestors }, { store }, zone, DashboardProperties.Ingest(backfillDays = backfill), projects, { source, _ -> failures += source }) { now }

    @Test
    fun `three backfill days end yesterday and a second run leaves the same table`() {
        val store = MemoryStore()
        val run = runner(store, listOf(Constant("cloudflare")))
        run.runOnce()
        val first = store.rows.toMap()
        run.runOnce()

        assertEquals(listOf("2026-10-24", "2026-10-25", "2026-10-26"), store.rows.values.map { it.day.toString() }.sorted())
        assertEquals(first, store.rows)
    }

    @Test
    fun `an ingestor that throws is reported by name and the others still run`() {
        val store = MemoryStore()
        val failures = mutableListOf<String>()
        val broken = object : DashboardIngestor {
            override val source = "broken"
            override fun ingest(day: LocalDate, zone: ZoneId): List<DashboardDailyRow> = throw IllegalStateException("token=secret")
        }
        runner(store, listOf(broken, Constant("ga")), failures = failures).runOnce()

        assertEquals(listOf("broken"), failures)
        assertEquals(3, store.rows.values.count { it.source == "ga" })
    }

    @Test
    fun `with projects each project is ingested on its own`() {
        val env = MockEnvironment()
            .withProperty("skeleton.projects.alpha.hosts", "alpha.test").withProperty("skeleton.projects.alpha.schema", "alpha")
            .withProperty("skeleton.projects.beta.hosts", "beta.test").withProperty("skeleton.projects.beta.schema", "beta")
        val store = MemoryStore()
        runner(store, listOf(Constant("cloudflare")), backfill = 1, projects = ProjectRegistry.from(env, null)).runOnce()

        assertTrue(store.projects.toSet() == setOf("alpha", "beta"), store.projects.toString())
    }
}
