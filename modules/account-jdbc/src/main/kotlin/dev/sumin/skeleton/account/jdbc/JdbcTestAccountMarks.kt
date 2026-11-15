package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.TestAccountMark
import dev.sumin.skeleton.account.TestAccountMarks
import dev.sumin.skeleton.common.account.TestAccountFilter
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.Instant
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 시험 계정 표 `account_test_marks` — 계정당 한 줄. 이미 있으면 처음 표시한 줄을 지킨다(`insertIgnore` — 예외를 삼키면 PG 트랜잭션이 죽는다).
 * 넣었는지는 먼저 읽어 판정한다: 두 운영자가 같은 순간 표시하면 감사 줄이 둘 남을 수 있을 뿐 표는 한 줄이다.
 */
class JdbcTestAccountMarks(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : TestAccountMarks {
    private val insert = dialect.insertIgnore("account_test_marks", listOf("account_id", "marked_by", "marked_at"), listOf("account_id"))

    override fun mark(accountId: String, by: String, at: Instant): Boolean {
        if (find(accountId) != null) return false
        jdbc.update(insert, mapOf("account_id" to accountId, "marked_by" to by, "marked_at" to dialect.instantParam(at)))
        return true
    }

    override fun clear(accountId: String): Boolean = jdbc.update("delete from account_test_marks where account_id = :id", mapOf("id" to accountId)) == 1

    override fun find(accountId: String): TestAccountMark? =
        jdbc.query("select account_id, marked_by, marked_at from account_test_marks where account_id = :id", mapOf("id" to accountId)) { rs, _ ->
            TestAccountMark(rs.getString("account_id"), rs.getString("marked_by"), dialect.readInstant(rs, "marked_at")!!)
        }.firstOrNull()
}

/** 집계 질의에 붙이는 조건 — 표시 표에 줄이 없는 계정만 통과한다. 별칭 `tm` 을 쓴다 */
class JdbcTestAccountFilter : TestAccountFilter {
    override fun notTestSql(accountColumn: String) = "not exists (select 1 from account_test_marks tm where tm.account_id = $accountColumn)"
}
