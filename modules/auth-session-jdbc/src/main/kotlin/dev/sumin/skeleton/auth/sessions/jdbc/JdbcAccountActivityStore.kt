package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.auth.sessions.AccountActivityStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.time.LocalDate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * `auth_account_activity` — 하루에 한 줄 (계정 id, 날짜). 쓰기는 충돌하면 아무것도 하지 않는 insert([SqlDialect.insertIgnore]) 한 문장이라 같은 날 여러 번(여러 인스턴스)이어도 한 줄이다.
 * 날짜는 `LocalDate` 로 바인딩한다 — 두 DB 모두 `date` 열이라 시간대 변환이 없다.
 */
class JdbcAccountActivityStore(private val jdbc: NamedParameterJdbcTemplate, dialect: SqlDialect) : AccountActivityStore {
    private val insert = dialect.insertIgnore("auth_account_activity", listOf("account_id", "day"), listOf("account_id", "day"))

    override fun recordActive(accountId: String, day: LocalDate) {
        jdbc.update(insert, mapOf("account_id" to accountId, "day" to day))
    }

    override fun eraseAccount(accountId: String) {
        jdbc.update("delete from auth_account_activity where account_id = :id", mapOf("id" to accountId))
    }

    override fun purgeBefore(day: LocalDate): Int = jdbc.update("delete from auth_account_activity where day < :day", mapOf("day" to day))
}
