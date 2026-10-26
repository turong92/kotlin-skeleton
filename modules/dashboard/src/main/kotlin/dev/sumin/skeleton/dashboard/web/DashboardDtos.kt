package dev.sumin.skeleton.dashboard.web

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardFreshness
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import java.time.Instant

// 계약 C1 (docs/dashboard-http-contract.md) — 필드 이름이 곧 프런트 `@skeleton/dashboard` 의 타입이다

data class DashboardRangeResponse(val from: String, val to: String, val zone: String, val interval: DashboardInterval)

data class DashboardPreviousResponse(val from: String, val to: String)

data class DashboardFreshnessResponse(val kind: DashboardFreshness.Kind, val asOf: Instant?)

data class DashboardPointResponse(val at: Instant, val value: Long)

data class DashboardMetricResponse(
    val key: String,
    val label: String,
    val unit: DashboardUnit,
    val source: String,
    val status: DashboardResult.Status,
    val freshness: DashboardFreshnessResponse?,
    /** 돈이 아니면 원소 하나(`currency: null`), 돈이면 통화마다 하나 (설정 통화가 먼저, 나머지는 금액이 큰 순). 숫자가 없으면(`status` ≠ OK) 빈 목록 */
    val value: List<DashboardAmount>,
    val previousValue: List<DashboardAmount>?,
    val series: List<DashboardPointResponse>,
    val previousSeries: List<DashboardPointResponse>?,
    val link: String?,
)

data class DashboardRankingResponse(
    val key: String,
    val group: String,
    val label: String,
    val source: String,
    val status: DashboardResult.Status,
    val freshness: DashboardFreshnessResponse?,
    val columns: List<String>,
    val rows: List<DashboardRow>,
)

data class DashboardFunnelStepResponse(val key: String, val label: String, val reached: Long, val rate: Double)

data class DashboardFunnelReferenceResponse(val key: String, val label: String, val value: Long, val rate: Double)

/** `status` — `OK` · `TOO_LARGE`(묶음이 상한을 넘었다, `steps` 는 비어 있다 — 기간을 줄인다) · `ERROR`(계산이 실패했다, `steps` 는 비어 있다) */
data class DashboardFunnelResponse(val status: String, val steps: List<DashboardFunnelStepResponse>, val reference: DashboardFunnelReferenceResponse?)

data class DashboardOverviewResponse(
    val range: DashboardRangeResponse,
    val previous: DashboardPreviousResponse?,
    val metrics: List<DashboardMetricResponse>,
    val rankings: List<DashboardRankingResponse>,
    val funnel: DashboardFunnelResponse?,
)
