package dev.sumin.skeleton.dashboard

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardFreshness
import dev.sumin.skeleton.common.dashboard.DashboardFunnel
import dev.sumin.skeleton.common.dashboard.DashboardFunnelStep
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import dev.sumin.skeleton.dashboard.web.DashboardFreshnessResponse
import dev.sumin.skeleton.dashboard.web.DashboardFunnelReferenceResponse
import dev.sumin.skeleton.dashboard.web.DashboardFunnelResponse
import dev.sumin.skeleton.dashboard.web.DashboardFunnelStepResponse
import dev.sumin.skeleton.dashboard.web.DashboardMetricResponse
import dev.sumin.skeleton.dashboard.web.DashboardOverviewResponse
import dev.sumin.skeleton.dashboard.web.DashboardPointResponse
import dev.sumin.skeleton.dashboard.web.DashboardPreviousResponse
import dev.sumin.skeleton.dashboard.web.DashboardRangeResponse
import dev.sumin.skeleton.dashboard.web.DashboardRankingResponse
import org.slf4j.LoggerFactory

/**
 * 기여 빈(타일 · 순위 · 퍼널)을 모아 C1 응답으로 조립한다. 계산은 [DashboardCache] 를 지나므로 같은 범위를 여러 번 물어도 기여는 캐시 주기당 한 번 불린다.
 * 기여가 예외를 던져도 그 카드만 `ERROR` 로 두고 나머지를 돌려준다 (로그에는 예외 종류만 — 메시지에 비밀이 있을 수 있다).
 */
class DashboardAssembler(
    private val metrics: () -> List<DashboardMetric>,
    private val rankings: () -> List<DashboardRanking>,
    private val funnel: () -> DashboardFunnel?,
    private val cache: DashboardCache,
    private val currency: String,
) {
    fun overview(period: DashboardPeriod, compare: Boolean): DashboardOverviewResponse {
        val tiles = metrics().sortedWith(compareBy({ it.order }, { it.key }))
        val values = tiles.associate { it.key to value(it, period.range) }
        return DashboardOverviewResponse(
            range = DashboardRangeResponse(period.fromDate.toString(), period.toDate.toString(), period.zone.id, period.interval),
            previous = if (compare) DashboardPreviousResponse(period.previousFromDate.toString(), period.previousToDate.toString()) else null,
            metrics = tiles.map { metric(it, period, compare, values.getValue(it.key)) },
            rankings = rankings().sortedWith(compareBy({ it.group }, { it.order }, { it.key })).map { ranking(it, period.range, OVERVIEW_ROWS) },
            funnel = funnel(period.range, tiles, values),
        )
    }

    /** 「전체 보기」 — 없는 키는 `DASHBOARD.RANKING_NOT_FOUND` */
    fun ranking(key: String, period: DashboardPeriod, limit: Int): DashboardRankingResponse {
        val ranking = rankings().firstOrNull { it.key == key }
            ?: throw DashboardException(DashboardErrorCode.RANKING_NOT_FOUND, "Unknown ranking: $key")
        return ranking(ranking, period.range, limit)
    }

    private fun metric(m: DashboardMetric, period: DashboardPeriod, compare: Boolean, current: Cached<DashboardResult<List<DashboardAmount>>>): DashboardMetricResponse {
        val range = period.range
        val withPrevious = compare && m.comparable
        val previous = if (withPrevious) value(m, period.previous).value else null
        return DashboardMetricResponse(
            key = m.key,
            label = m.label,
            unit = m.unit,
            source = m.source,
            status = current.value.status,
            freshness = freshness(current),
            value = amounts(m, current.value),
            previousValue = previous?.takeIf { it.status == DashboardResult.Status.OK }?.let { amounts(m, it) },
            series = points(m, range, period),
            previousSeries = if (withPrevious && previous?.status == DashboardResult.Status.OK) points(m, period.previous, period) else null,
            link = m.link,
        )
    }

    private fun value(m: DashboardMetric, range: DashboardRange): Cached<DashboardResult<List<DashboardAmount>>> =
        cache.get("m|${m.key}|v|${range.from}|${range.to}", cacheable = ::cacheable) {
            safe(m.key) { m.value(range) }
        }

    private fun points(m: DashboardMetric, range: DashboardRange, period: DashboardPeriod): List<DashboardPointResponse> {
        val result = cache.get("m|${m.key}|s|${range.from}|${range.to}|${period.interval}", cacheable = ::cacheable) {
            safe(m.key) { m.series(range, period.interval) }
        }.value
        return if (result.status == DashboardResult.Status.OK) result.data.orEmpty().map { DashboardPointResponse(it.at, it.value) } else emptyList()
    }

    private fun ranking(r: DashboardRanking, range: DashboardRange, limit: Int): DashboardRankingResponse {
        val cached = cache.get("r|${r.key}|${range.from}|${range.to}|$limit", cacheable = ::cacheable) {
            safe(r.key) { r.top(range, limit) }
        }
        val result = cached.value
        return DashboardRankingResponse(
            key = r.key,
            group = r.group,
            label = r.label,
            source = r.source,
            status = result.status,
            freshness = freshness(cached),
            columns = r.columns,
            rows = if (result.status == DashboardResult.Status.OK) result.data.orEmpty() else emptyList(),
        )
    }

    private fun funnel(range: DashboardRange, tiles: List<DashboardMetric>, values: Map<String, Cached<DashboardResult<List<DashboardAmount>>>>): DashboardFunnelResponse? {
        val definition = funnel() ?: return null
        val steps = cache.get("f|${range.from}|${range.to}", cacheable = { it != null }) {
            try {
                val cohort = definition.cohort.members(range)
                FunnelCounts(cohort.size.toLong(), definition.steps.sortedWith(compareBy({ it.order }, { it.key })).map { it to it.reached(cohort) })
            } catch (e: Exception) {
                log.warn("Dashboard funnel failed: {}", e.javaClass.simpleName)
                null
            }
        }.value ?: return null
        val size = steps.size
        val reference = definition.referenceMetricKey?.let { key ->
            val tile = tiles.firstOrNull { it.key == key } ?: return@let null
            val result = values.getValue(tile.key).value
            val visits = result.data?.firstOrNull()?.amount?.takeIf { result.status == DashboardResult.Status.OK } ?: return@let null
            DashboardFunnelReferenceResponse(tile.key, tile.label, visits, if (visits > 0) size.toDouble() / visits else 0.0)
        }
        return DashboardFunnelResponse(
            steps = listOf(DashboardFunnelStepResponse(definition.cohort.key, definition.cohort.label, size, if (size > 0) 1.0 else 0.0)) +
                steps.reached.map { (step, n) -> DashboardFunnelStepResponse(step.key, step.label, n, if (size > 0) n.toDouble() / size else 0.0) },
            reference = reference,
        )
    }

    private class FunnelCounts(val size: Long, val reached: List<Pair<DashboardFunnelStep, Long>>)

    /** 돈은 설정 통화가 먼저, 나머지는 금액이 큰 순. 돈이 아니거나 숫자가 없으면 그대로 */
    private fun amounts(m: DashboardMetric, result: DashboardResult<List<DashboardAmount>>): List<DashboardAmount> {
        if (result.status != DashboardResult.Status.OK) return emptyList()
        val data = result.data.orEmpty()
        if (m.unit != DashboardUnit.MONEY) return data
        return data.sortedWith(compareBy<DashboardAmount> { it.currency != currency }.thenByDescending { it.amount })
    }

    private fun <T> freshness(cached: Cached<DashboardResult<T>>): DashboardFreshnessResponse? {
        val result = cached.value
        val f = result.freshness ?: if (result.status == DashboardResult.Status.OK) DashboardFreshness(DashboardFreshness.Kind.LIVE, cached.computedAt) else null
        return f?.let { DashboardFreshnessResponse(it.kind, it.asOf) }
    }

    private fun <T> cacheable(result: DashboardResult<T>) = result.status != DashboardResult.Status.ERROR

    private fun <T> safe(key: String, call: () -> DashboardResult<T>): DashboardResult<T> =
        try {
            call()
        } catch (e: Exception) {
            log.warn("Dashboard card {} failed: {}", key, e.javaClass.simpleName)
            DashboardResult(DashboardResult.Status.ERROR)
        }

    private companion object {
        const val OVERVIEW_ROWS = 10
        val log = LoggerFactory.getLogger(DashboardAssembler::class.java)
    }
}
