package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.common.dashboard.DashboardAmount
import dev.sumin.skeleton.common.dashboard.DashboardCohortMember
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 일별 활동 기록: 하루 한 줄(멱등) · 계정 삭제 · 보존 기간 경계, 그 위의 활성 이용자(기간 경계 · 직전 기간 비교)와 「돌아옴」 단계. 두 방언 */
class AccountActivityDbTest {
    private val store = JdbcAccountActivityStore(SessionDb.jdbc, DbTestDatabase.dialect)
    private val zone = ZoneId.of("Asia/Seoul")
    private val metric = ActiveUsersMetric(SessionDb.jdbc)

    @BeforeTest
    fun clean() { SessionDb.jdbc.update("delete from auth_account_activity", emptyMap<String, Any>()) }

    private fun day(text: String) = LocalDate.parse(text)
    private fun count() = SessionDb.jdbc.queryForObject("select count(*) from auth_account_activity", emptyMap<String, Any>(), Long::class.java)
    private fun week(from: String, to: String) = DashboardRange(day(from).atStartOfDay(zone).toInstant(), day(to).atStartOfDay(zone).toInstant(), zone)

    @Test
    fun `many writes on the same day leave one line, another day or account is another line`() {
        repeat(5) { store.recordActive("acc_a", day("2026-10-20")) }
        store.recordActive("acc_a", day("2026-10-21"))
        store.recordActive("acc_b", day("2026-10-20"))

        assertEquals(3, count())
    }

    @Test
    fun `erasing an account removes only its lines, and the retention purge keeps the boundary day`() {
        store.recordActive("acc_a", day("2026-10-20"))
        store.recordActive("acc_a", day("2026-10-21"))
        store.recordActive("acc_b", day("2026-10-20"))
        store.eraseAccount("acc_a")
        assertEquals(1, count())

        store.recordActive("acc_b", day("2026-10-25"))
        assertEquals(0, store.purgeBefore(day("2026-10-20")))   // 10-20 은 「앞선」 날이 아니라 남는다
        assertEquals(2, count())
        assertEquals(1, store.purgeBefore(day("2026-10-21")))
        assertEquals(1, count())
    }

    @Test
    fun `active users counts distinct accounts in the period, both end days in and the next day out, and the previous period compares on the same definition`() {
        listOf("acc_a" to "2026-10-20", "acc_a" to "2026-10-26", "acc_b" to "2026-10-26", "acc_c" to "2026-10-19", "acc_a" to "2026-10-19", "acc_d" to "2026-10-27")
            .forEach { (account, at) -> store.recordActive(account, day(at)) }

        val current = week("2026-10-20", "2026-10-27")     // 10-20 ~ 10-26
        val previous = week("2026-10-13", "2026-10-20")    // 10-13 ~ 10-19
        assertEquals(listOf(DashboardAmount(2)), metric.value(current).data)    // a, b — d 는 10-27 이라 밖이다
        assertEquals(listOf(DashboardAmount(2)), metric.value(previous).data)   // c, a — 같은 정의로 직전 기간도 센다
        assertTrue(metric.comparable)
        assertEquals(listOf(1L, 0L, 0L, 0L, 0L, 0L, 2L), metric.series(current, DashboardInterval.DAY).data!!.map { it.value })
        assertEquals(1, metric.series(week("2026-10-26", "2026-10-27"), DashboardInterval.HOUR).data!!.size)   // 시간별은 그날의 합계 한 점
    }

    @Test
    fun `a member came back only when there is activity on a day after the sign-up day`() {
        store.recordActive("acc_a", day("2026-10-20"))   // 가입한 날만
        store.recordActive("acc_b", day("2026-10-20"))
        store.recordActive("acc_b", day("2026-10-21"))   // 다음 날
        val joined = Instant.parse("2026-10-20T23:30:00Z")   // 서울 10-21 08:30 — 활동 시간대의 날짜는 10-21
        val joinedEarly = Instant.parse("2026-10-20T01:00:00Z")   // 서울 10-20 10:00

        assertEquals(1, ReturnedStep(SessionDb.jdbc) { zone }.reached(listOf(DashboardCohortMember("acc_a", joinedEarly), DashboardCohortMember("acc_b", joinedEarly))))
        assertEquals(0, ReturnedStep(SessionDb.jdbc) { zone }.reached(listOf(DashboardCohortMember("acc_b", joined))))   // 가입일이 10-21 이면 10-21 활동은 「이후」가 아니다
    }
}
