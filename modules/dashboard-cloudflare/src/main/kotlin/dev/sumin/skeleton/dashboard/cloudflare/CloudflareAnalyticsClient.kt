package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.time.Instant
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Cloudflare 가 오류를 답했거나 답을 읽을 수 없다. 메시지에 토큰 · 응답 본문을 싣지 않는다 */
class CloudflareQueryException(message: String) : RuntimeException(message)

/** 그룹 하나의 한 줄 — [visits] · [pageviews] 는 표본 간격으로 환산한 값, [dimension] 은 그룹 차원의 값(전체 그룹이면 빈 문자열) */
data class CloudflareRow(val dimension: String, val visits: Long, val pageviews: Long)

/**
 * Web Analytics(RUM) GraphQL — `rumPageloadEventsAdaptiveGroups`(계정 범위, 사이트 태그와 시각 범위로 거른다). 그룹 여러 개를 별칭으로 한 요청에 싣는다.
 * 환산: 값 × `avg.sampleInterval`(반올림) — 표본 추출된 이벤트를 원래 규모로. 페이지뷰 = `count`, 방문 = `sum.visits`.
 * 응답 모양은 계획 C4 의 추측이다 — 실제 토큰으로 재서 맞춘다.
 */
class CloudflareAnalyticsClient(private val http: ExternalHttpClient) {
    enum class Grouping(val alias: String, val dimension: String?, val limit: Int) {
        TOTAL("total", null, 1),
        HOUR("hour", "datetimeHour", 48),
        REFERRER("referrer", "refererHost", TOP),
        PATH("path", "requestPath", TOP),
        COUNTRY("country", "countryName", TOP),
        DEVICE("device", "deviceType", TOP),
    }

    private val mapper = JsonMapper.builder().build()

    fun query(properties: CloudflareProperties, from: Instant, to: Instant, groupings: List<Grouping>): Map<Grouping, List<CloudflareRow>> {
        val body = mapOf(
            "query" to queryText(groupings),
            "variables" to mapOf(
                "accountTag" to properties.accountId,
                "filter" to mapOf("siteTag" to properties.siteTag, "datetime_geq" to from.toString(), "datetime_lt" to to.toString()),
            ),
        )
        val response = http.post(properties.clientName, "/client/v4/graphql", body, String::class.java) {
            baseUrl(properties.baseUrl)
            header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apiToken}")
            timeout(properties.timeout)
            loggingTag("dashboard.cloudflare.analytics")
        }.block() ?: throw CloudflareQueryException("Cloudflare returned no body")
        val root = try { mapper.readTree(response) } catch (e: Exception) { throw CloudflareQueryException("Cloudflare returned an unreadable body") }
        if (root["errors"]?.let { !it.isNull && it.size() > 0 } == true) throw CloudflareQueryException("Cloudflare reported an error")
        val account = root["data"]?.get("viewer")?.get("accounts")?.get(0) ?: throw CloudflareQueryException("Cloudflare returned no account data")
        return groupings.associateWith { grouping -> (account[grouping.alias]?.let { nodes -> nodes.toList().map { row(grouping, it) } } ?: emptyList()) }
    }

    private fun row(grouping: Grouping, node: JsonNode): CloudflareRow {
        val interval = node["avg"]?.get("sampleInterval")?.asDouble()?.takeIf { it > 0 } ?: 1.0
        fun scaled(value: Double) = Math.round(value * interval)
        return CloudflareRow(
            dimension = grouping.dimension?.let { node["dimensions"]?.get(it)?.asString() ?: "" } ?: "",
            visits = scaled(node["sum"]?.get("visits")?.asDouble() ?: 0.0),
            pageviews = scaled(node["count"]?.asDouble() ?: 0.0),
        )
    }

    private fun queryText(groupings: List<Grouping>): String {
        val blocks = groupings.joinToString("\n") { g ->
            val order = if (g.dimension == null) "" else ", orderBy: [count_DESC]"
            val dims = g.dimension?.let { " dimensions { $it }" }.orEmpty()
            "      ${g.alias}: rumPageloadEventsAdaptiveGroups(filter: \$filter, limit: ${g.limit}$order) { count sum { visits } avg { sampleInterval }$dims }"
        }
        return "query Analytics(\$accountTag: string!, \$filter: AccountRumPageloadEventsAdaptiveGroupsFilter_InputObject!) {\n  viewer {\n    accounts(filter: { accountTag: \$accountTag }) {\n$blocks\n    }\n  }\n}"
    }

    private companion object {
        const val TOP = 50
    }
}
