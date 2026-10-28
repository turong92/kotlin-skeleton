package dev.sumin.skeleton.coupon.jdbc

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardBuckets
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardPoint
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardRanking
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardRow
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 쿠폰 사용 수 - 기간 안에 `used_at` 이 있는 `USED` 쿠폰 수. 이후 전액 환불로 되돌려진(`AVAILABLE`) 쿠폰은 세지 않는다. 지금 상태 기준이다 */
class CouponUsedMetric(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardMetric {
    override val key = "coupon.used"
    override val label = "Coupons used"
    override val unit = DashboardUnit.COUNT
    override val source = "db"
    override val order = 60
    override val link = "/admin/coupons"

    override fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>> {
        val count = jdbc.queryForObject(
            "select count(*) from coupons where status = 'USED' and used_at >= :from and used_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
            Long::class.java,
        ) ?: 0L
        return DashboardResult(DashboardResult.Status.OK, listOf(DashboardAmount(count)))
    }

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?): DashboardResult<List<DashboardPoint>> {
        val times = jdbc.query(
            "select used_at as at from coupons where status = 'USED' and used_at >= :from and used_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
        ) { rs, _ -> dialect.readInstant(rs, "at")!! to 1 }
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, times) { it.size.toLong() })
    }
}

/** 많이 쓰인 쿠폰 순위 - 기간 안에 쓰인 쿠폰을 정의별로 센다("앱 통계" 카드의 탭) */
class CouponTopUsedRanking(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardRanking {
    override val key = "coupon.top-used"
    override val group = "app"
    override val label = "Most used coupons"
    override val columns = listOf("Coupon", "Used")
    override val source = "db"
    override val order = 30

    override fun top(range: DashboardRange, limit: Int): DashboardResult<List<DashboardRow>> {
        val rows = jdbc.query(
            "select d.name as name, count(*) as n from coupons c join coupon_definitions d on d.id = c.definition_id " +
                "where c.status = 'USED' and c.used_at >= :from and c.used_at < :to group by d.id, d.name order by n desc, d.name limit :limit",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to), "limit" to limit),
        ) { rs, _ -> DashboardRow(rs.getString("name"), listOf(rs.getLong("n"))) }
        return DashboardResult(DashboardResult.Status.OK, rows)
    }
}
