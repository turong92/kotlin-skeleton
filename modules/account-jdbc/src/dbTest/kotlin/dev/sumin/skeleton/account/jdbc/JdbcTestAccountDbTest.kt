package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.common.dashboard.DashboardInterval
import dev.sumin.skeleton.common.dashboard.DashboardRange
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 두 DB 에서 — 시험 계정 표시: 표 · 집계 조건 · 공지 대상 `all` · 가입 타일. 표시된 계정만 빠지고 지우면 돌아온다 */
class JdbcTestAccountDbTest {
    private val at = Instant.parse("2026-11-10T00:00:00Z")
    private val marks = JdbcTestAccountMarks(AccountDb.jdbc, DbTestDatabase.dialect)
    private val filter = JdbcTestAccountFilter()
    private val day = DashboardRange(Instant.parse("2026-11-10T00:00:00Z"), Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)

    @BeforeTest fun clean() = AccountDb.clean()   // 표시 줄은 계정 줄과 함께 지워진다(on delete cascade)

    private fun account(id: String, status: AccountStatus = AccountStatus.ACTIVE, vararg roles: String) {
        assertTrue(AccountDb.accounts.insert(Account(id, "$id@example.com", true, status, roles.toSet(), null, null, null, at.plusSeconds(60), at.plusSeconds(60)), emptyList()))
    }

    @Test
    fun `mark keeps the first operator and time, clear removes it, both say whether anything changed`() {
        account("t1")
        assertTrue(marks.mark("t1", "boss", at))
        assertFalse(marks.mark("t1", "other", at.plusSeconds(5)), "already marked")
        val mark = marks.find("t1")!!
        assertEquals("boss", mark.markedBy)
        assertEquals(at, mark.markedAt)
        assertTrue(marks.clear("t1"))
        assertFalse(marks.clear("t1"))
        assertNull(marks.find("t1"))
    }

    @Test
    fun `the filter condition keeps unmarked accounts and drops the marked one - and a cleared mark comes back`() {
        account("u1"); account("u2"); account("u3")
        marks.mark("u2", "boss", at)
        fun survivors() = AccountDb.jdbc.queryForList(
            "select a.id from accounts a where ${filter.notTestSql("a.id")} order by a.id", emptyMap<String, Any>(), String::class.java,
        )
        assertEquals(listOf("u1", "u3"), survivors())
        marks.clear("u2")
        assertEquals(listOf("u1", "u2", "u3"), survivors())
    }

    @Test
    fun `the all audience leaves a test account out, a role audience still reaches it`() {
        account("a1", AccountStatus.ACTIVE, "EDITOR"); account("a2", AccountStatus.ACTIVE, "EDITOR"); account("a3")
        marks.mark("a2", "boss", at)
        val all = JdbcAccountAudiences.all(AccountDb.jdbc, filter)
        assertEquals(2, all.count(null))
        assertEquals(listOf("a1", "a3"), all.page(null, null, 10))
        val role = JdbcAccountAudiences.role(AccountDb.jdbc)
        assertEquals(listOf("a1", "a2"), role.page("EDITOR", null, 10), "naming a role is a deliberate choice - the test account is still addressable")
    }

    @Test
    fun `new sign-ups and the funnel cohort leave test accounts out`() {
        account("s1"); account("s2"); account("s3")
        marks.mark("s3", "boss", at)
        val metric = SignedUpMetric(AccountDb.jdbc, DbTestDatabase.dialect, filter)
        assertEquals(2L, metric.value(day).data!!.single().amount)
        assertEquals(2L, metric.series(day, DashboardInterval.DAY, null).data!!.sumOf { it.value })
        assertEquals(listOf("s1", "s2"), SignedUpCohort(AccountDb.jdbc, DbTestDatabase.dialect, filter).members(day, 100).map { it.accountId }.sorted())
    }
}
