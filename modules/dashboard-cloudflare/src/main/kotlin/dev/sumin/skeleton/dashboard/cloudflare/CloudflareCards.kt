package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardUnit

/** 방문(`cloudflare.visits`) · 페이지뷰(`cloudflare.pageviews`) 타일. 숫자는 Cloudflare 가 센다 — 쿠키 없음, 정적 페이지 포함 */
class CloudflareMetric(
    override val key: String,
    override val label: String,
    override val order: Int,
    private val kind: String,
    private val cloudflare: CloudflareSource,
) : DashboardMetric {
    override val unit = DashboardUnit.COUNT
    override val source = CloudflareKinds.SOURCE

    override fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>> {
        val totals = cloudflare.totals(range)
        return DashboardResult(totals.status, totals.data?.let { (visits, pageviews) -> listOf(DashboardAmount(if (kind == CloudflareKinds.VISITS) visits else pageviews)) }, totals.freshness)
    }

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?) = cloudflare.points(range, interval, kind)
}

/** 순위 탭 하나 (유입 · 페이지 · 국가 · 기기) */
class CloudflareRanking(
    override val key: String,
    override val group: String,
    override val label: String,
    override val columns: List<String>,
    override val order: Int,
    private val kind: String,
    private val grouping: CloudflareAnalyticsClient.Grouping,
    private val data: CloudflareSource,
) : DashboardRanking {
    override val source = CloudflareKinds.SOURCE

    override fun top(range: DashboardRange, limit: Int) = data.top(range, kind, grouping, limit)
}
