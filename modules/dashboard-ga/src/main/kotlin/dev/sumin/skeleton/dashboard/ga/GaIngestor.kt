package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import dev.sumin.skeleton.dashboard.DashboardIngestor
import java.time.LocalDate
import java.time.ZoneId

/**
 * 하루치(어제까지 — 오늘은 GA 가 아직 확정하지 않았다)를 일별 표 행으로: 채널 · 캠페인 · 첫 페이지 · 페이지 이동(`참조 → 경로`) · 새/재방문 상위 50. 경로는 쿼리 문자열과 조각을 뗀다.
 * 핵심 이벤트 이름(`key-event`)이 있을 때만 채널 · 캠페인 · 첫 페이지의 전환 행(`*_conv`)이 생긴다. 같은 날을 다시 적재하면 같은 행이다 (저장소가 덮어쓴다). 키가 비면 아무것도 하지 않는다.
 * 날짜는 GA 속성의 시간대 기준이다 — 속성과 대시보드 시간대를 맞춰 두는 것은 운영자의 몫(문서).
 */
class GaIngestor(private val client: GaDataClient, private val settings: GaSettings) : DashboardIngestor {
    override val source = GaKinds.SOURCE
    override val kinds = setOf(
        GaKinds.CHANNEL, GaKinds.CAMPAIGN, GaKinds.LANDING, GaKinds.TRANSITION, GaKinds.RETURNING,
        // 핵심 이벤트를 껐다 켜도 옛 전환 줄이 남지 않게 늘 비운다
        GaKinds.CHANNEL + GaKinds.CONVERSION_SUFFIX, GaKinds.CAMPAIGN + GaKinds.CONVERSION_SUFFIX, GaKinds.LANDING + GaKinds.CONVERSION_SUFFIX,
    )

    override fun active() = settings.current().connected

    override fun ingest(day: LocalDate, zone: ZoneId): List<DashboardDailyRow> {
        val properties = settings.current()
        if (!properties.connected) return emptyList()
        val conversion = properties.keyEvent.takeIf { it.isNotBlank() }?.let { "keyEvents:$it" }
        val rows = ArrayList<DashboardDailyRow>()
        fun ranked(kind: String, dimensions: List<String>, clean: (List<String>) -> String, withConversion: Boolean, metric: String = "sessions") {
            val metrics = if (withConversion && conversion != null) listOf(metric, conversion) else listOf(metric)
            val report = client.report(properties, day, dimensions, metrics, LIMIT)
            rows += merged(day, kind, report.map { clean(it.dimensions) to it.metrics[0] })
            if (withConversion && conversion != null) rows += merged(day, kind + GaKinds.CONVERSION_SUFFIX, report.map { clean(it.dimensions) to it.metrics.getOrElse(1) { 0L } })
        }
        ranked(GaKinds.CHANNEL, listOf("sessionDefaultChannelGroup"), { it[0] }, true)
        ranked(GaKinds.CAMPAIGN, listOf("sessionCampaignName"), { it[0] }, true)
        ranked(GaKinds.LANDING, listOf("landingPage"), { withoutQuery(it[0]) }, true)
        ranked(GaKinds.TRANSITION, listOf("pageReferrer", "pagePath"), { "${withoutQuery(it[0])} → ${withoutQuery(it[1])}" }, false, metric = "screenPageViews")
        ranked(GaKinds.RETURNING, listOf("newVsReturning"), { it[0].lowercase() }, false, metric = "activeUsers")
        return rows
    }

    private fun merged(day: LocalDate, kind: String, values: List<Pair<String, Long>>): List<DashboardDailyRow> {
        val sums = LinkedHashMap<String, Long>()
        values.forEach { (dimension, value) -> sums.merge(label(dimension).take(MAX_DIMENSION), value, Long::plus) }
        return sums.map { (dimension, value) -> DashboardDailyRow(day, GaKinds.SOURCE, kind, dimension, value) }
    }

    /** 빈 차원은 「(none)」 — 빈 문자열은 표에서 전체 값이라 차원으로 쓸 수 없다 */
    private fun label(raw: String) = raw.ifBlank { "(none)" }

    /** 쿼리 · 조각을 떼고, 요청 로그와 같은 규칙(`LogMasker.maskRequestPath` — 앱이 더한 주소 속 비밀 조각 규칙, 없으면 그대로)으로 공유 링크 토큰 같은 값을 가린다 */
    private fun withoutQuery(url: String): String {
        val bare = url.substringBefore('?').substringBefore('#')
        // `pageReferrer` 는 전체 주소(`https://호스트/경로`)라 앱의 `^/e/…` 같은 경로 규칙이 걸리도록 호스트는 따로 두고 경로 부분만 마스킹한다
        val absolute = ABSOLUTE.matchEntire(bare) ?: return LogMasker.current.maskRequestPath(bare)
        val (origin, path) = absolute.destructured
        return origin + if (path.isEmpty()) "" else LogMasker.current.maskRequestPath(path)
    }

    private companion object {
        val ABSOLUTE = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://[^/]+)(.*)$")
        const val LIMIT = 50
        const val MAX_DIMENSION = 200
    }
}
