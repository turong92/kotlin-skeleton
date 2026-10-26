package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.dashboard.DashboardBuckets
import dev.sumin.skeleton.common.dashboard.DashboardFreshness
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardPoint
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.dashboard.Cached
import dev.sumin.skeleton.dashboard.DailySeries
import dev.sumin.skeleton.dashboard.DashboardCache
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.slf4j.LoggerFactory

/** 표(`dashboard_daily`)의 `source` 와 종류 이름 — 적재기와 카드가 같은 이름으로 읽고 쓴다 */
object CloudflareKinds {
    const val SOURCE = "cloudflare"
    const val VISITS = "visits"
    const val PAGEVIEWS = "pageviews"
    const val REFERRER = "referrer"
    const val PATH = "path"
    const val COUNTRY = "country"
    const val DEVICE = "device"

    /** 경로는 쿼리 문자열과 조각을 뗀다 — 적재분과 오늘 직접 질의분이 같은 키로 합쳐진다 */
    fun withoutQuery(path: String) = path.substringBefore('?').substringBefore('#')
}

/**
 * 카드가 읽는 길 하나: 어제까지는 일별 표(`DailySeries.past`), 오늘은 Cloudflare 를 직접(서버 캐시 `live-cache`, 프로젝트마다 한 번) — 둘을 합친다.
 * 화면 요청은 이 캐시를 지나므로 운영자가 여럿 열어도 외부 호출은 캐시 주기당 프로젝트당 한 번이다.
 * 표가 없는 앱(`dashboard-jdbc` 없음)에서 지난 날이 걸린 기간은 `NO_HISTORY`, 연결 정보가 비면 `NOT_CONNECTED`, 외부 호출이 실패하면 `ERROR`.
 */
class CloudflareSource(
    private val client: CloudflareAnalyticsClient,
    private val settings: CloudflareSettings,
    private val series: DailySeries,
    private val cache: DashboardCache,
    private val zone: () -> ZoneId,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun connected(): Boolean = settings.current().connected

    /** 기간의 (방문, 페이지뷰) 합 */
    fun totals(range: DashboardRange): DashboardResult<Pair<Long, Long>> = compose(range) { split, past ->
        var visits = 0L
        var pageviews = 0L
        past(CloudflareKinds.VISITS).forEach { visits += it.value }
        past(CloudflareKinds.PAGEVIEWS).forEach { pageviews += it.value }
        val today = split.today?.let { live(it, listOf(CloudflareAnalyticsClient.Grouping.TOTAL)) }
        today?.value?.get(CloudflareAnalyticsClient.Grouping.TOTAL)?.forEach { visits += it.visits; pageviews += it.pageviews }
        Composed(visits to pageviews, today?.computedAt)
    }

    /** 그래프 점. 오늘 하루만 보면 시간별(직접), 그 밖에는 일별 표 + 오늘 합계를 오늘 칸에 */
    fun points(range: DashboardRange, interval: DashboardInterval, kind: String): DashboardResult<List<DashboardPoint>> = compose(range) { split, past ->
        val zone = zone()
        val samples = ArrayList<Pair<Instant, Long>>()
        past(kind).forEach { samples += it.day.atStartOfDay(zone).toInstant() to it.value }
        val todayRange = split.today
        var fetchedAt: Instant? = null
        if (todayRange != null) {
            if (interval == DashboardInterval.HOUR) {
                val live = live(todayRange, listOf(CloudflareAnalyticsClient.Grouping.HOUR))
                live.value[CloudflareAnalyticsClient.Grouping.HOUR].orEmpty().forEach { row ->
                    Instant.parse(row.dimension).let { samples += it to if (kind == CloudflareKinds.VISITS) row.visits else row.pageviews }
                }
                fetchedAt = live.computedAt
            } else {
                val live = live(todayRange, listOf(CloudflareAnalyticsClient.Grouping.TOTAL))
                live.value[CloudflareAnalyticsClient.Grouping.TOTAL].orEmpty().forEach { samples += todayRange.from to if (kind == CloudflareKinds.VISITS) it.visits else it.pageviews }
                fetchedAt = live.computedAt
            }
        }
        // 지난 하루만 시간별로 달라고 하면 표에는 일별 값뿐이라 그날 한 점만 나온다
        Composed(DashboardBuckets.series(range, interval, samples) { it.sum() }, fetchedAt)
    }

    /** 순위 한 개 — 일별 표의 차원별 합 + 오늘 직접 질의, 큰 순 */
    fun top(range: DashboardRange, kind: String, grouping: CloudflareAnalyticsClient.Grouping, limit: Int): DashboardResult<List<DashboardRow>> =
        compose(range) { split, past ->
            val sums = HashMap<String, Long>()
            past(kind).forEach { if (it.dimension.isNotEmpty()) sums.merge(it.dimension, it.value, Long::plus) }
            val today = split.today?.let { live(it, listOf(grouping)) }
            today?.value?.get(grouping)?.forEach { sums.merge(label(if (kind == CloudflareKinds.PATH) CloudflareKinds.withoutQuery(it.dimension) else it.dimension), if (kind == CloudflareKinds.PATH) it.pageviews else it.visits, Long::plus) }
            Composed(sums.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(limit).map { DashboardRow(it.key, listOf(it.value)) }, today?.computedAt)
        }

    /** 하루치를 표에 옮길 값 — 적재기가 쓴다 (캐시를 거치지 않는다) */
    fun day(day: LocalDate, zone: ZoneId): Map<CloudflareAnalyticsClient.Grouping, List<CloudflareRow>> {
        val from = day.atStartOfDay(zone).toInstant()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant()
        return client.query(settings.current(), from, to, listOf(
            CloudflareAnalyticsClient.Grouping.TOTAL, CloudflareAnalyticsClient.Grouping.REFERRER, CloudflareAnalyticsClient.Grouping.PATH,
            CloudflareAnalyticsClient.Grouping.COUNTRY, CloudflareAnalyticsClient.Grouping.DEVICE,
        ))
    }

    /** 외부 호출 결과(또는 실패)를 캐시한다 — 실패도 [FAILURE_TTL] 동안 담아 장애 중에 폴링마다 Cloudflare 를 다시 부르지 않는다 */
    private fun live(range: DashboardRange, groupings: List<CloudflareAnalyticsClient.Grouping>): Cached<Map<CloudflareAnalyticsClient.Grouping, List<CloudflareRow>>> {
        val properties = settings.current()
        val cached = cache.get<Outcome>(
            "cf|${properties.siteTag}|${range.from}|${range.to}|${groupings.joinToString { it.alias }}",
            ttl = properties.liveCache,
            ttlOf = { if (it.failed) FAILURE_TTL else properties.liveCache },
        ) {
            try {
                Outcome(client.query(properties, range.from, range.to, groupings))
            } catch (e: Exception) {
                log.warn("Cloudflare analytics failed: {}", e.javaClass.simpleName)
                log.debug("Cloudflare analytics failure", e)
                Outcome(null)
            }
        }
        val data = cached.value.data ?: throw CloudflareCachedFailure()
        return Cached(data, cached.computedAt)
    }

    private class Outcome(val data: Map<CloudflareAnalyticsClient.Grouping, List<CloudflareRow>>?) {
        val failed: Boolean get() = data == null
    }

    private class Composed<T>(val data: T, val fetchedAt: Instant?)

    /** 연결 확인 → 지난 날(표) 읽기 → [body] → 신선도 붙이기. 예외는 `ERROR` 로 바꾼다 (로그에는 예외 종류만) */
    private fun <T> compose(
        range: DashboardRange,
        body: (DailySeries.Split, (String) -> List<DashboardDailyRow>) -> Composed<T>,
    ): DashboardResult<T> {
        if (!connected()) return DashboardResult(DashboardResult.Status.NOT_CONNECTED)
        val split = series.split(range)
        val pastCache = HashMap<String, DashboardResult<List<DashboardDailyRow>>>()
        var lastIngested: DashboardFreshness? = null
        val past = { kind: String ->
            val result = pastCache.getOrPut(kind) { series.past(CloudflareKinds.SOURCE, kind, split) }
            lastIngested = result.freshness
            if (result.status == DashboardResult.Status.NO_HISTORY) throw NoHistory()
            result.data.orEmpty()
        }
        return try {
            val composed = body(split, past)
            val freshness = composed.fetchedAt?.let { DashboardFreshness(DashboardFreshness.Kind.CACHED, it) } ?: lastIngested
            DashboardResult(DashboardResult.Status.OK, composed.data, freshness)
        } catch (e: NoHistory) {
            DashboardResult(DashboardResult.Status.NO_HISTORY)
        } catch (e: CloudflareCachedFailure) {
            DashboardResult(DashboardResult.Status.ERROR)   // 처음 실패는 이미 warn 으로 남겼다 — 캐시된 실패를 다시 던질 때는 로그하지 않는다
        } catch (e: Exception) {
            log.warn("Cloudflare analytics failed: {}", e.javaClass.simpleName)
            log.debug("Cloudflare analytics failure", e)
            DashboardResult(DashboardResult.Status.ERROR)
        }
    }

    private class NoHistory : RuntimeException(null, null, false, false)

    /** 이미 로그한 외부 호출 실패가 캐시에서 다시 나왔다 */
    private class CloudflareCachedFailure : RuntimeException("Cloudflare analytics is failing (retrying after a short pause)", null, false, false)

    companion object {
        private val FAILURE_TTL: java.time.Duration = java.time.Duration.ofSeconds(60)

        /** 차원 값이 비면 「(none)」 — 빈 문자열은 표에서 전체 값이라 차원으로 쓸 수 없다 */
        fun label(raw: String): String = raw.ifBlank { "(none)" }
    }
}
