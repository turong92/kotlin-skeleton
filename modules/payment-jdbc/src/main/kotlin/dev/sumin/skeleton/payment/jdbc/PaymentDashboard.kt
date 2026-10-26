package dev.sumin.skeleton.payment.jdbc

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardBuckets
import dev.sumin.skeleton.common.dashboard.DashboardCohortMember
import dev.sumin.skeleton.common.dashboard.DashboardFunnelStep
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardPoint
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 순매출 = 기간 안 `paid_at` 의 `paid_amount` 합 − 기간 안 `completed_at` 의 성공(`SUCCEEDED`) 환불 합, **통화별로 따로** (환율 변환 없이 합치지 않는다).
 * 부분 환불도, 결제사 쪽 취소(`origin = PROVIDER`)도 뺀다. 실패 · 대기 · 거절 · 철회 환불은 돈이 나가지 않았으므로 빼지 않는다.
 * 환불은 완료된 기간의 매출에서 빠진다 — 지난 기간에 결제한 주문의 환불은 지난 기간 값을 바꾸지 않고 이번 기간을 줄인다.
 * 그래프 시리즈는 한 통화만: [preferredCurrency](`skeleton.dashboard.currency`)가 있으면 그것, 없으면 기간 순매출 절댓값이 큰 통화.
 */
class NetRevenueMetric(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val preferredCurrency: String,
) : DashboardMetric {
    override val key = "payment.net-revenue"
    override val label = "Net revenue"
    override val unit = DashboardUnit.MONEY
    override val source = "db"
    override val order = 40
    override val link = "/admin/payments"

    override fun value(range: DashboardRange) = DashboardResult(DashboardResult.Status.OK, net(range).map { (currency, amount) -> DashboardAmount(amount, currency) })

    override fun series(range: DashboardRange, interval: DashboardInterval): DashboardResult<List<DashboardPoint>> {
        val currency = seriesCurrency(range) ?: return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval) { 0L })
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval) { net(it)[currency] ?: 0L })
    }

    private fun seriesCurrency(range: DashboardRange): String? {
        val net = net(range)
        if (preferredCurrency.isNotBlank() && preferredCurrency in net) return preferredCurrency
        return net.maxByOrNull { kotlin.math.abs(it.value) }?.key
    }

    /** 통화 → 순매출. 결제 또는 환불이 있는 통화만, 통화 코드순 */
    private fun net(range: DashboardRange): Map<String, Long> {
        val params = mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to))
        val paid = sumByCurrency(
            "select currency, sum(paid_amount) as total from payment_orders where paid_at >= :from and paid_at < :to and paid_amount is not null group by currency",
            params,
        )
        val refunded = sumByCurrency(
            "select currency, sum(amount) as total from payment_refunds where status = 'SUCCEEDED' and completed_at >= :from and completed_at < :to group by currency",
            params,
        )
        return (paid.keys + refunded.keys).sorted().associateWith { (paid[it] ?: 0L) - (refunded[it] ?: 0L) }
    }

    private fun sumByCurrency(sql: String, params: Map<String, Any?>): Map<String, Long> {
        val sums = HashMap<String, Long>()
        jdbc.query(sql, params) { rs -> sums[rs.getString("currency").trim()] = rs.getLong("total") }
        return sums
    }
}

/** 결제 건수 — 기간 안 `paid_at` 이 있는 주문 수 (대기 · 실패 · 만료는 `paid_at` 이 없다). 이후 환불돼도 센다 */
class PaidCountMetric(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardMetric {
    override val key = "payment.paid-count"
    override val label = "Payments"
    override val unit = DashboardUnit.COUNT
    override val source = "db"
    override val order = 50
    override val link = "/admin/payments"

    override fun value(range: DashboardRange) = DashboardResult(DashboardResult.Status.OK, listOf(DashboardAmount(count(range))))

    override fun series(range: DashboardRange, interval: DashboardInterval) =
        DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, ::count))

    private fun count(range: DashboardRange): Long =
        jdbc.queryForObject(
            "select count(*) from payment_orders where paid_at >= :from and paid_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
            Long::class.java,
        ) ?: 0L
}

/** 퍼널 단계 「결제했다」 — 묶음 중 `paid_at` 이 있는 주문을 가진 계정. 계정 id 는 1000 개씩 끊어 묻는다 */
class PaidStep(private val jdbc: NamedParameterJdbcTemplate) : DashboardFunnelStep {
    override val key = "payment.paid"
    override val label = "Paid"
    override val order = 30

    override fun reached(cohort: List<DashboardCohortMember>): Long =
        cohort.map { it.accountId }.distinct().chunked(1000).sumOf { ids ->
            jdbc.queryForObject(
                "select count(distinct account_id) from payment_orders where account_id in (:ids) and paid_at is not null",
                mapOf("ids" to ids),
                Long::class.java,
            ) ?: 0L
        }
}
