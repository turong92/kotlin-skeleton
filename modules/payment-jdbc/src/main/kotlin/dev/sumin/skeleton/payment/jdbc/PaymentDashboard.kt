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
import dev.sumin.skeleton.payment.PaymentOrders
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 어느 주문을 세나 — **지금 끼워 둔 결제사 키의 모드와 같은 모드로 만든 주문만**. 테스트 키로 시작했다가 실키로 바꾼 앱의 테스트 매출이 순매출에 영원히 남지 않게 하고,
 * 테스트 키 앱(견본 앱 · 로컬)에서는 테스트 주문이 그대로 보인다. [modes] 는 켜진 결제사마다 (결제사 id, 모드 이름)을 부를 때마다 돌려준다(프로젝트마다 다르다).
 * 켜져 있지 않은 결제사(껐거나 모르는)의 주문은 `LIVE` 만 센다 — 진짜 돈이 조용히 사라지지 않고, 테스트 주문은 보이지 않는다.
 * 환불은 주문과 조인해 같은 기준을 건다. SQL 조각은 주문 표의 별칭 `o` 를 쓴다.
 */
class CurrentModeOrders(private val modes: () -> List<Pair<String, String>>) {
    fun clause(): Pair<String, Map<String, Any>> {
        val wired = modes()
        val params = HashMap<String, Any>()
        val parts = wired.mapIndexed { i, (provider, mode) ->
            params["mp$i"] = provider
            params["mm$i"] = mode
            "(o.provider = :mp$i and o.provider_mode = :mm$i)"
        }.toMutableList()
        if (wired.isEmpty()) {
            parts += "o.provider_mode = 'LIVE'"
        } else {
            params["wired"] = wired.map { it.first }
            parts += "(o.provider not in (:wired) and o.provider_mode = 'LIVE')"
        }
        // 결제사 없이 끝난 쿠폰 0원 주문(free)은 열린 결제사의 모드를 따른다 - 시험 키 앱에서도 건수 1 · 매출 0 으로 보이게. 결제 면제(exempt)는 매출 · 건수 · 퍼널 어디에도 들어가지 않는다
        params["freeModes"] = wired.map { it.second }.distinct().ifEmpty { listOf("LIVE") }
        parts += "(o.provider = '${PaymentOrders.FREE_PROVIDER}' and o.provider_mode in (:freeModes))"
        return parts.joinToString(" or ", prefix = "((", postfix = ") and o.provider <> '${PaymentOrders.EXEMPT_PROVIDER}')") to params
    }
}

/**
 * 순매출 = 기간 안 `paid_at` 의 `paid_amount` 합 − 기간 안 `completed_at` 의 성공(`SUCCEEDED`) 환불 합, **통화별로 따로** (환율 변환 없이 합치지 않는다).
 * 부분 환불도, 결제사 쪽 취소(`origin = PROVIDER`)도 뺀다. 실패 · 대기 · 거절 · 철회 환불은 돈이 나가지 않았으므로 빼지 않는다.
 * 환불은 **완료된 기간**의 매출에서 빠진다 — 지난 기간에 결제한 주문의 환불은 지난 기간 값을 바꾸지 않고 이번 기간을 줄인다.
 * 단 값은 지금 상태 기준이다: 차지백 환불이 이겨서 되돌려지면(`REVERSED`) 그 환불이 완료됐던 기간의 순매출이 **소급해서 올라간다**.
 * 그래프 시리즈는 조립기가 정해 준 한 통화만 — 결제 · 환불 시각과 금액을 범위당 질의 두 번으로 읽어 칸에 나눈다.
 */
class NetRevenueMetric(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val orders: CurrentModeOrders,
) : DashboardMetric {
    override val key = "payment.net-revenue"
    override val label = "Net revenue"
    override val unit = DashboardUnit.MONEY
    override val source = "db"
    override val order = 40
    override val link = "/admin/payments"

    override fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>> {
        val (mode, modeParams) = orders.clause()
        val params = range(range) + modeParams
        val paid = sumByCurrency(
            "select o.currency as currency, sum(o.paid_amount) as total from payment_orders o where o.paid_at >= :from and o.paid_at < :to and o.paid_amount is not null and $mode group by o.currency",
            params,
        )
        val refunded = sumByCurrency(
            "select r.currency as currency, sum(r.amount) as total from payment_refunds r join payment_orders o on o.id = r.order_id " +
                "where r.status = 'SUCCEEDED' and r.completed_at >= :from and r.completed_at < :to and $mode group by r.currency",
            params,
        )
        return DashboardResult(DashboardResult.Status.OK, (paid.keys + refunded.keys).sorted().map { DashboardAmount((paid[it] ?: 0L) - (refunded[it] ?: 0L), it) })
    }

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?): DashboardResult<List<DashboardPoint>> {
        if (currency == null) return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series<Long>(range, interval, emptyList()) { 0L })
        val (mode, modeParams) = orders.clause()
        val params = range(range) + modeParams + ("currency" to currency)
        val samples = ArrayList<Pair<Instant, Long>>()
        jdbc.query(
            "select o.paid_at as at, o.paid_amount as amount from payment_orders o where o.paid_at >= :from and o.paid_at < :to and o.paid_amount is not null and o.currency = :currency and $mode",
            params,
        ) { rs -> samples += dialect.readInstant(rs, "at")!! to rs.getLong("amount") }
        jdbc.query(
            "select r.completed_at as at, r.amount as amount from payment_refunds r join payment_orders o on o.id = r.order_id " +
                "where r.status = 'SUCCEEDED' and r.completed_at >= :from and r.completed_at < :to and r.currency = :currency and $mode",
            params,
        ) { rs -> samples += dialect.readInstant(rs, "at")!! to -rs.getLong("amount") }
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, samples) { it.sum() })
    }

    private fun range(range: DashboardRange) = mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to))

    private fun sumByCurrency(sql: String, params: Map<String, Any?>): Map<String, Long> {
        val sums = HashMap<String, Long>()
        jdbc.query(sql, params) { rs -> sums[rs.getString("currency").trim()] = rs.getLong("total") }
        return sums
    }
}

/** 결제 건수 — 기간 안 `paid_at` 이 있는 주문 수 (대기 · 실패 · 만료는 `paid_at` 이 없다). 이후 환불돼도 센다. 모드 기준은 [CurrentModeOrders] */
class PaidCountMetric(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect, private val orders: CurrentModeOrders) : DashboardMetric {
    override val key = "payment.paid-count"
    override val label = "Payments"
    override val unit = DashboardUnit.COUNT
    override val source = "db"
    override val order = 50
    override val link = "/admin/payments"

    override fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>> {
        val (mode, modeParams) = orders.clause()
        val count = jdbc.queryForObject(
            "select count(*) from payment_orders o where o.paid_at >= :from and o.paid_at < :to and $mode",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)) + modeParams,
            Long::class.java,
        ) ?: 0L
        return DashboardResult(DashboardResult.Status.OK, listOf(DashboardAmount(count)))
    }

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?): DashboardResult<List<DashboardPoint>> {
        val (mode, modeParams) = orders.clause()
        val times = jdbc.query(
            "select o.paid_at as at from payment_orders o where o.paid_at >= :from and o.paid_at < :to and $mode",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)) + modeParams,
        ) { rs, _ -> dialect.readInstant(rs, "at")!! to 1 }
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, times) { it.size.toLong() })
    }
}

/** 퍼널 단계 「결제했다」 — 묶음 중 `paid_at` 이 있는 주문(지금 모드의)을 가진 계정. 계정 id 는 1000 개씩 끊어 묻는다 */
class PaidStep(private val jdbc: NamedParameterJdbcTemplate, private val orders: CurrentModeOrders) : DashboardFunnelStep {
    override val key = "payment.paid"
    override val label = "Paid"
    override val order = 30

    override fun reached(cohort: List<DashboardCohortMember>): Long {
        val (mode, modeParams) = orders.clause()
        return cohort.map { it.accountId }.distinct().chunked(1000).sumOf { ids ->
            jdbc.queryForObject(
                "select count(distinct o.account_id) from payment_orders o where o.account_id in (:ids) and o.paid_at is not null and $mode",
                mapOf("ids" to ids) + modeParams,
                Long::class.java,
            ) ?: 0L
        }
    }
}
