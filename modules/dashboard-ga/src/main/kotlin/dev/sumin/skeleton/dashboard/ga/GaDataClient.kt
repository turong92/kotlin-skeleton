package dev.sumin.skeleton.dashboard.ga

import dev.sumin.skeleton.common.http.ExternalHttpClient
import java.time.LocalDate
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper

class GaQueryException(message: String) : RuntimeException(message)

/** 보고서 한 줄 — 차원 값들과 지표 값들(GA 는 숫자도 문자열로 준다) */
data class GaRow(val dimensions: List<String>, val metrics: List<Long>)

/**
 * GA4 Data API v1beta — `runReport`(날짜 범위 하나) · `runRealtimeReport`(최근 30분). 접근 토큰은 [GaTokenSource] 가 준다.
 * 응답 모양은 계획 C4 의 추측이다 — 실제 속성으로 재서 맞춘다 (특히 핵심 이벤트 지표 이름 `keyEvents:<이벤트>` 와 실시간 차원).
 */
class GaDataClient(private val http: ExternalHttpClient, private val tokens: GaTokenSource) {
    private val mapper = JsonMapper.builder().build()

    fun report(properties: GaProperties, day: LocalDate, dimensions: List<String>, metrics: List<String>, limit: Int, orderMetric: String = metrics.first()): List<GaRow> =
        call(
            properties, "runReport",
            mapOf(
                "dateRanges" to listOf(mapOf("startDate" to day.toString(), "endDate" to day.toString())),
                "dimensions" to dimensions.map { mapOf("name" to it) },
                "metrics" to metrics.map { mapOf("name" to it) },
                "limit" to limit,
                "orderBys" to listOf(mapOf("metric" to mapOf("metricName" to orderMetric), "desc" to true)),
            ),
        )

    fun realtime(properties: GaProperties, dimensions: List<String>, metrics: List<String>, limit: Int): List<GaRow> =
        call(
            properties, "runRealtimeReport",
            buildMap {
                put("metrics", metrics.map { mapOf("name" to it) })
                if (dimensions.isNotEmpty()) put("dimensions", dimensions.map { mapOf("name" to it) })
                put("limit", limit)
            },
        )

    private fun call(properties: GaProperties, method: String, body: Map<String, Any>): List<GaRow> {
        val token = tokens.token(properties)
        val response = http.post(properties.clientName, "/v1beta/properties/{property}:$method", body, String::class.java) {
            baseUrl(properties.baseUrl)
            uriVariable("property", properties.propertyId.removePrefix("properties/"))
            header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            timeout(properties.timeout)
            loggingTag("dashboard.ga.$method")
        }.block() ?: throw GaQueryException("Google returned no body")
        val root = try { mapper.readTree(response) } catch (e: Exception) { throw GaQueryException("Google returned an unreadable body") }
        return root["rows"]?.toList().orEmpty().map { row ->
            GaRow(
                row["dimensionValues"]?.toList().orEmpty().map { it["value"]?.asString().orEmpty() },
                row["metricValues"]?.toList().orEmpty().map { Math.round(it["value"]?.asString()?.toDoubleOrNull() ?: 0.0) },
            )
        }
    }
}
