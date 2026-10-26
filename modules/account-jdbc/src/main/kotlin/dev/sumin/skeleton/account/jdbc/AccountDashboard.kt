package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardBuckets
import dev.sumin.skeleton.common.dashboard.DashboardCohortMember
import dev.sumin.skeleton.common.dashboard.DashboardCohortSource
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardMetric
import dev.sumin.skeleton.common.dashboard.DashboardPoint
import dev.sumin.skeleton.common.dashboard.DashboardRange
import dev.sumin.skeleton.common.dashboard.DashboardResult
import dev.sumin.skeleton.common.dashboard.DashboardUnit
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 신규 가입 — 기간 안 `created_at`. 탈퇴해 개인정보를 지운(ERASED) 계정도 센다: 가입한 사실은 남는다.
 * [SignedUpCohort] 는 같은 기간의 가입자를 퍼널의 묶음으로 넘긴다 (계정 id + 가입 시각, 메모리에서만).
 */
class SignedUpMetric(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardMetric {
    override val key = "account.signed-up"
    override val label = "New sign-ups"
    override val unit = DashboardUnit.PEOPLE
    override val source = "db"
    override val order = 20

    override fun value(range: DashboardRange) = DashboardResult(DashboardResult.Status.OK, listOf(DashboardAmount(count(range))))

    override fun series(range: DashboardRange, interval: DashboardInterval, currency: String?): DashboardResult<List<DashboardPoint>> {
        // 칸마다 세지 않는다 — 가입 시각만 한 번 읽어 칸에 나눈다
        val times = jdbc.query(
            "select created_at from accounts where created_at >= :from and created_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
        ) { rs, _ -> dialect.readInstant(rs, "created_at")!! to 1 }
        return DashboardResult(DashboardResult.Status.OK, DashboardBuckets.series(range, interval, times) { it.size.toLong() })
    }

    private fun count(range: DashboardRange): Long =
        jdbc.queryForObject(
            "select count(*) from accounts where created_at >= :from and created_at < :to",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to)),
            Long::class.java,
        ) ?: 0L
}

class SignedUpCohort(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : DashboardCohortSource {
    override val key = "account.signed-up"
    override val label = "Signed up"

    override fun members(range: DashboardRange, limit: Int): List<DashboardCohortMember> =
        jdbc.query(
            "select id, created_at from accounts where created_at >= :from and created_at < :to limit :limit",
            mapOf("from" to dialect.instantParam(range.from), "to" to dialect.instantParam(range.to), "limit" to limit + 1),
        ) { rs, _ -> DashboardCohortMember(rs.getString("id"), dialect.readInstant(rs, "created_at")!!) }
}
