package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.dashboard.DashboardDailyRow
import dev.sumin.skeleton.dashboard.DashboardIngestor
import java.time.LocalDate
import java.time.ZoneId

/**
 * 하루치를 일별 표 행으로: 전체 방문 · 페이지뷰(차원 빈 문자열) + 유입 · 경로 · 국가 · 기기 상위. 경로는 쿼리 문자열과 조각을 뗀다 (개인을 알아볼 수 있는 값이 표에 남지 않게).
 * 같은 날을 다시 적재하면 같은 행이다 (저장소가 덮어쓴다). 연결 정보가 비면 아무것도 하지 않는다.
 */
class CloudflareIngestor(private val data: CloudflareSource) : DashboardIngestor {
    override val source = CloudflareKinds.SOURCE

    override fun ingest(day: LocalDate, zone: ZoneId): List<DashboardDailyRow> {
        if (!data.connected()) return emptyList()
        val loaded = data.day(day, zone)
        val total = loaded[CloudflareAnalyticsClient.Grouping.TOTAL].orEmpty()
        val rows = ArrayList<DashboardDailyRow>()
        rows += row(day, CloudflareKinds.VISITS, "", total.sumOf { it.visits })
        rows += row(day, CloudflareKinds.PAGEVIEWS, "", total.sumOf { it.pageviews })
        rows += dimensional(day, CloudflareKinds.REFERRER, loaded[CloudflareAnalyticsClient.Grouping.REFERRER].orEmpty(), { it }, { it.visits })
        rows += dimensional(day, CloudflareKinds.PATH, loaded[CloudflareAnalyticsClient.Grouping.PATH].orEmpty(), CloudflareKinds::withoutQuery, { it.pageviews })
        rows += dimensional(day, CloudflareKinds.COUNTRY, loaded[CloudflareAnalyticsClient.Grouping.COUNTRY].orEmpty(), { it }, { it.visits })
        rows += dimensional(day, CloudflareKinds.DEVICE, loaded[CloudflareAnalyticsClient.Grouping.DEVICE].orEmpty(), { it }, { it.visits })
        return rows
    }

    private fun dimensional(day: LocalDate, kind: String, rows: List<CloudflareRow>, clean: (String) -> String, value: (CloudflareRow) -> Long): List<DashboardDailyRow> {
        val sums = LinkedHashMap<String, Long>()
        rows.forEach { sums.merge(CloudflareSource.label(clean(it.dimension)).take(MAX_DIMENSION), value(it), Long::plus) }
        return sums.map { (dimension, n) -> row(day, kind, dimension, n) }
    }

    private fun row(day: LocalDate, kind: String, dimension: String, value: Long) = DashboardDailyRow(day, CloudflareKinds.SOURCE, kind, dimension, value)

    private companion object {
        const val MAX_DIMENSION = 200
    }
}
