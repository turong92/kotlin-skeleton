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
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Duration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 활성 이용자 — 기간 안에 세션을 쓴(`auth_sessions.last_used_at`, 로그인 · 토큰 갱신 모두 남는다) 서로 다른 계정 수. **근사치다**:
 * 갱신 시각을 덮어써서 지난 기간 값이 적게 나온다. 그래서 직전 기간은 계산하지 않는다(`comparable = false`) — 화면은 증감 대신 「비교 불가」.
 */
class ActiveUsersMetric(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardMetric {
    override val key = "auth-session.active"
    override val label = "Active users"
    override val unit = DashboardUnit.PEOPLE
    override val source = "db"
    override val order = 30
    override val comparable = false

    override fun value(range: DashboardRange) = DashboardResult(DashboardResult.Status.OK, listOf(DashboardAmount(active(range))))

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?): DashboardResult<List<DashboardPoint>> {
        // 칸마다 세지 않는다 — (마지막 사용 시각, 계정) 만 한 번 읽어 칸마다 서로 다른 계정을 센다
        val used = jdbc.query(
            "select account_id, last_used_at from auth_sessions where last_used_at >= :from and last_used_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
        ) { rs, _ -> dialect.readInstant(rs, "last_used_at")!! to rs.getString("account_id") }
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, used) { it.distinct().size.toLong() })
    }

    private fun active(range: DashboardRange): Long =
        jdbc.queryForObject(
            "select count(distinct account_id) from auth_sessions where last_used_at >= :from and last_used_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
            Long::class.java,
        ) ?: 0L
}

/**
 * 퍼널 단계 「돌아왔다」 — 묶음 중 마지막 사용 시각이 가입 + 24시간 이후인 계정. 세션마다 마지막 사용 시각 하나만 남으므로 이것도 근사치다
 * (그 사이 다시 오지 않은 사람이 가입 직후 갱신한 세션은 세지 않고, 중간에 쓴 날은 알 수 없다). 계정 id 는 1000 개씩 끊어 묻는다.
 */
class ReturnedStep(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardFunnelStep {
    override val key = "auth-session.returned"
    override val label = "Came back"
    override val order = 20

    override fun reached(cohort: List<DashboardCohortMember>): Long {
        val joined = cohort.associate { it.accountId to it.joinedAt }
        var reached = 0L
        joined.keys.chunked(CHUNK).forEach { ids ->
            jdbc.query(
                "select account_id, max(last_used_at) as last_used_at from auth_sessions where account_id in (:ids) group by account_id",
                mapOf("ids" to ids),
            ) { rs ->
                val lastUsed = dialect.readInstant(rs, "last_used_at")
                if (lastUsed != null && !lastUsed.isBefore(joined.getValue(rs.getString("account_id")).plus(AFTER))) reached++
            }
        }
        return reached
    }

    private companion object {
        const val CHUNK = 1000
        val AFTER: Duration = Duration.ofDays(1)
    }
}
