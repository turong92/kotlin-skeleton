package dev.sumin.skeleton.dashboard.cloudflare

import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.time.Instant
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Cloudflare 가 오류를 답했거나 답을 읽을 수 없다. 메시지에 토큰 · 응답 본문을 싣지 않는다 */
class CloudflareQueryException(message: String) : RuntimeException(message)

/** 그룹 하나의 한 줄 — [visits] · [pageviews] 는 Cloudflare 가 돌려준 추정값 그대로, [dimension] 은 그룹 차원의 값(전체 그룹이면 빈 문자열) */
data class CloudflareRow(val dimension: String, val visits: Long, val pageviews: Long)

/**
 * Web Analytics(RUM) GraphQL — `rumPageloadEventsAdaptiveGroups`(계정 범위, 사이트 태그와 시각 범위로 거른다). 그룹 여러 개를 별칭으로 한 요청에 싣는다.
 * 페이지뷰 = `count`, 방문 = `sum.visits`. **표본 간격(`avg.sampleInterval`)으로 다시 곱하지 않는다** — 실측(2026-10-27, 무료 계정): `sampleInterval` 이 10 인 줄의 `count` · `visits` 는
 * 모두 10 의 배수(표본 하나가 `count: 10`)라 Cloudflare 가 이미 추정값으로 돌려준다. 표본 간격은 질의 범위가 클수록 커진다(하루 질의는 1, 한 달 질의는 10) — 그래서 큰 범위의 숫자는 거칠다.
 * 같은 호스트에서 온 이동(내부 이동)은 `refererHost` 가 자기 호스트이고 `visits` 가 0 이다(`count` 만 있다) — 유입 순위는 방문 기준이라 방문 0 줄을 뺀다.
 * 질의 모양(변수 이름 · 타입 · 필터)은 실측으로 맞다.
 */
class CloudflareAnalyticsClient(private val http: ExternalHttpClient) {
    /** [orderBy] — 상위 [limit] 를 고르는 기준. 방문 기준 순위는 방문으로 정렬해야 방문 0 인 내부 이동 줄이 상위 칸을 차지하지 않는다 */
    enum class Grouping(val alias: String, val dimension: String?, val limit: Int, val orderBy: String?) {
        TOTAL("total", null, 1, null),
        HOUR("hour", "datetimeHour", 48, null),
        REFERRER("referrer", "refererHost", TOP, "sum_visits_DESC"),
        PATH("path", "requestPath", TOP, "count_DESC"),
        COUNTRY("country", "countryName", TOP, "sum_visits_DESC"),
        DEVICE("device", "deviceType", TOP, "sum_visits_DESC"),
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
        return CloudflareRow(
            dimension = grouping.dimension?.let { node["dimensions"]?.get(it)?.asString() ?: "" } ?: "",
            visits = Math.round(node["sum"]?.get("visits")?.asDouble() ?: 0.0),
            pageviews = Math.round(node["count"]?.asDouble() ?: 0.0),
        )
    }

    private fun queryText(groupings: List<Grouping>): String {
        val blocks = groupings.joinToString("\n") { g ->
            val order = g.orderBy?.let { ", orderBy: [$it]" }.orEmpty()
            val dims = g.dimension?.let { " dimensions { $it }" }.orEmpty()
            "      ${g.alias}: rumPageloadEventsAdaptiveGroups(filter: \$filter, limit: ${g.limit}$order) { count sum { visits } avg { sampleInterval }$dims }"
        }
        return "query Analytics(\$accountTag: string!, \$filter: AccountRumPageloadEventsAdaptiveGroupsFilter_InputObject!) {\n  viewer {\n    accounts(filter: { accountTag: \$accountTag }) {\n$blocks\n    }\n  }\n}"
    }

    private companion object {
        const val TOP = 50
    }
}
