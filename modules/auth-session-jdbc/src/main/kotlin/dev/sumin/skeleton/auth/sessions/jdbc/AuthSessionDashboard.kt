package dev.sumin.skeleton.auth.sessions.jdbc

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
import java.time.LocalDate
import java.time.ZoneId
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 활성 이용자 — 기간 안에 로그인하거나 토큰을 새로고침한(= 서비스를 쓴) 서로 다른 계정 수. **정확한 일별 활동 기록**(`auth_account_activity`, 하루에 한 줄)에서 센다 —
 * 세션의 마지막 사용 시각을 덮어쓰는 방식이 아니라서 지난 기간도 같은 정의로 계산되고, 직전 기간과 비교할 수 있다(`comparable = true`).
 * 날짜는 활동 시간대(`skeleton.auth-session.activity.zone`)의 날짜이고, 기간은 대시보드 시간대의 날짜로 자른다 — 두 시간대를 같게 둔다.
 * 하루 안의 시간 단위는 기록하지 않는다: 시간별(`HOUR`) 그래프는 그날의 합계 한 점이다.
 */
class ActiveUsersMetric(private val jdbc: NamedParameterJdbcTemplate) : DashboardMetric {
    override val key = "auth-session.active"
    override val label = "Active users"
    override val unit = DashboardUnit.PEOPLE
    override val source = "db"
    override val order = 30

    override fun value(range: DashboardRange): DashboardResult<List<DashboardAmount>> {
        val count = jdbc.queryForObject(
            "select count(distinct account_id) from auth_account_activity where day >= :from and day <= :to",
            days(range), Long::class.java,
        ) ?: 0L
        return DashboardResult(DashboardResult.Status.OK, listOf(DashboardAmount(count)))
    }

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?): DashboardResult<List<DashboardPoint>> {
        // 칸마다 세지 않는다 — (날짜, 계정) 만 한 번 읽어 칸마다 서로 다른 계정을 센다
        val used = jdbc.query("select day, account_id from auth_account_activity where day >= :from and day <= :to", days(range)) { rs, _ ->
            rs.getObject("day", LocalDate::class.java).atStartOfDay(range.zone).toInstant() to rs.getString("account_id")
        }
        if (interval == DashboardInterval.HOUR) return DashboardResult(DashboardResult.Status.OK, listOf(DashboardPoint(range.from, used.map { it.second }.distinct().size.toLong())))
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, used) { it.distinct().size.toLong() })
    }

    /** 기간 `[from, to)` 가 덮는 날짜 `[from, to]`(양 끝 포함) */
    private fun days(range: DashboardRange) = mapOf(
        "from" to range.from.atZone(range.zone).toLocalDate(),
        "to" to range.to.minusNanos(1).atZone(range.zone).toLocalDate(),
    )
}

/**
 * 퍼널 단계 「돌아왔다」 — 묶음 중 가입한 날 **이후의 날**에 활동한 적이 있는 계정(일별 활동 기록의 가장 늦은 날이 가입일보다 뒤). 가입일은 활동 시간대의 날짜다.
 * 계정 id 는 1000 개씩 끊어 묻는다.
 */
class ReturnedStep(private val jdbc: NamedParameterJdbcTemplate, private val zone: () -> ZoneId) : DashboardFunnelStep {
    override val key = "auth-session.returned"
    override val label = "Came back"
    override val order = 20

    override fun reached(cohort: List<DashboardCohortMember>): Long {
        val zone = zone()
        val joined = cohort.associate { it.accountId to it.joinedAt.atZone(zone).toLocalDate() }
        var reached = 0L
        joined.keys.chunked(CHUNK).forEach { ids ->
            jdbc.query(
                "select account_id, max(day) as last_day from auth_account_activity where account_id in (:ids) group by account_id",
                mapOf("ids" to ids),
            ) { rs ->
                if (rs.getObject("last_day", LocalDate::class.java).isAfter(joined.getValue(rs.getString("account_id")))) reached++
            }
        }
        return reached
    }

    private companion object {
        const val CHUNK = 1000
    }
}
