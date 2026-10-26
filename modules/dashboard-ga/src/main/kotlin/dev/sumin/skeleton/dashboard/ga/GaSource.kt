package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.dashboard.DashboardFreshness
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardCache
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import org.slf4j.LoggerFactory

/** 표(`dashboard_daily`)의 `source` 와 종류 이름 — 적재기와 카드가 같은 이름으로 읽고 쓴다. `*_conv` 는 핵심 이벤트(전환) 수 */
internal object GaKinds {
    const val SOURCE = "ga"
    const val CHANNEL = "channel"
    const val CAMPAIGN = "campaign"
    const val LANDING = "landing"
    const val TRANSITION = "transition"
    const val RETURNING = "returning"
    const val CONVERSION_SUFFIX = "_conv"
}

/** 현재 방문자 한 번의 스냅샷 (최근 30분) */
data class GaRealtimeSnapshot(val activeUsers: Long, val pages: List<DashboardRow>, val countries: List<DashboardRow>)

/**
 * 카드가 읽는 길: **순위는 일별 표에서만** 읽는다 — GA 의 일반 보고서는 몇 시간 늦게 확정되므로 오늘은 적재도 질의도 하지 않고(오늘 칸은 비운다, 어제까지만),
 * 신선도는 「마지막 적재 시각」(`DAILY`)이다. 표가 없는 앱에서 지난 날이 걸린 기간은 `NO_HISTORY`, 키가 비면 `NOT_CONNECTED`.
 * 현재 방문자(실시간)만 직접 질의하고 서버 캐시(`realtime-cache`, 프로젝트마다 한 번)를 거친다.
 */
class GaSource(
    private val client: GaDataClient,
    private val settings: GaSettings,
    private val series: DailySeries,
    private val cache: DashboardCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun connected(): Boolean = settings.current().connected

    fun conversionsShown(): Boolean = settings.current().keyEvent.isNotBlank()

    /** 순위 한 개 — 표의 차원별 합. 값은 [세션] 또는 (전환 열이 켜졌으면) [세션, 전환] */
    fun top(range: DashboardRange, kind: String, limit: Int, labelOf: (String) -> String = { it }): DashboardResult<List<DashboardRow>> {
        if (!connected()) return DashboardResult(DashboardResult.Status.NOT_CONNECTED)
        return try {
            val split = series.split(range)
            val main = series.past(GaKinds.SOURCE, kind, split)
            if (main.status != DashboardResult.Status.OK) return DashboardResult(main.status)
            val conversions = if (conversionsShown() && kind != GaKinds.TRANSITION && kind != GaKinds.RETURNING) series.past(GaKinds.SOURCE, kind + GaKinds.CONVERSION_SUFFIX, split).data.orEmpty() else null
            val sessions = sum(main.data.orEmpty())
            val converted = conversions?.let(::sum)
            val rows = sessions.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(limit)
                .map { DashboardRow(labelOf(it.key), if (converted != null) listOf(it.value, converted[it.key] ?: 0L) else listOf(it.value)) }
            DashboardResult(DashboardResult.Status.OK, rows, main.freshness)
        } catch (e: Exception) {
            log.warn("GA ranking failed: {}", e.javaClass.simpleName)
            log.debug("GA ranking failure", e)
            DashboardResult(DashboardResult.Status.ERROR)
        }
    }

    private fun sum(rows: List<DashboardDailyRow>): Map<String, Long> {
        val sums = HashMap<String, Long>()
        rows.forEach { if (it.dimension.isNotEmpty()) sums.merge(it.dimension, it.value, Long::plus) }
        return sums
    }

    /** 현재 방문자 · 지금 보는 페이지 · 국가 */
    fun realtime(): DashboardResult<GaRealtimeSnapshot> {
        val properties = settings.current()
        if (!properties.connected) return DashboardResult(DashboardResult.Status.NOT_CONNECTED)
        return try {
            // 성공도 실패도 캐시한다 — 실패는 짧게(FAILURE_TTL) 담아 장애 중에 폴링마다 Google 을 다시 부르지 않는다
            val cached = cache.get<RealtimeOutcome>("ga-realtime|${properties.propertyId}", ttl = properties.realtimeCache, ttlOf = { if (it.snapshot == null) FAILURE_TTL else properties.realtimeCache }) {
                try {
                    val total = client.realtime(properties, emptyList(), listOf("activeUsers"), 1).firstOrNull()?.metrics?.firstOrNull() ?: 0L
                    val pages = client.realtime(properties, listOf("unifiedScreenName"), listOf("activeUsers"), REALTIME_ROWS).map { DashboardRow(it.dimensions.first().ifBlank { "(none)" }, it.metrics) }
                    val countries = client.realtime(properties, listOf("country"), listOf("activeUsers"), REALTIME_ROWS).map { DashboardRow(it.dimensions.first().ifBlank { "(none)" }, it.metrics) }
                    RealtimeOutcome(GaRealtimeSnapshot(total, pages, countries))
                } catch (e: Exception) {
                    log.warn("GA realtime failed: {}", e.javaClass.simpleName)
                    log.debug("GA realtime failure", e)
                    RealtimeOutcome(null)
                }
            }
            val snapshot = cached.value.snapshot ?: return DashboardResult(DashboardResult.Status.ERROR)
            DashboardResult(DashboardResult.Status.OK, snapshot, DashboardFreshness(DashboardFreshness.Kind.CACHED, cached.computedAt))
        } catch (e: Exception) {
            log.warn("GA realtime failed: {}", e.javaClass.simpleName)
            log.debug("GA realtime failure", e)
            DashboardResult(DashboardResult.Status.ERROR)
        }
    }

    private class RealtimeOutcome(val snapshot: GaRealtimeSnapshot?)

    private companion object {
        const val REALTIME_ROWS = 10
        val FAILURE_TTL: java.time.Duration = java.time.Duration.ofSeconds(60)
    }
}
