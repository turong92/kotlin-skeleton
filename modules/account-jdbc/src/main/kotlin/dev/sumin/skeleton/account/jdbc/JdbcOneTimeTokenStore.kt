package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.events.AccountEvent
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.token.OneTimeTokenStore
import dev.sumin.skeleton.account.token.TokenRow
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 한 번 쓰는 토큰 저장소 — 소비는 조건부 UPDATE 한 문장이라 두 DB 모두에서 정확히 한 요청만 이긴다 */
class JdbcOneTimeTokenStore(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : OneTimeTokenStore {
    override fun insert(row: TokenRow) {
        jdbc.update(
            """
            insert into account_tokens (token_hash, purpose, subject, account_id, payload, created_at, expires_at)
            values (:h, :purpose, :subject, :account, :payload, :created, :expires)
            """.trimIndent(),
            MapSqlParameterSource().addValue("h", row.hash).addValue("purpose", row.purpose).addValue("subject", row.subject).addValue("account", row.accountId)
                .addValue("payload", row.payload).addValue("created", dialect.instantParam(row.createdAt)).addValue("expires", dialect.instantParam(row.expiresAt)),
        )
    }

    override fun find(hash: String): TokenRow? =
        jdbc.query("select * from account_tokens where token_hash = :h", mapOf("h" to hash)) { rs, _ -> rs.row() }.firstOrNull()

    override fun consume(hash: String, purpose: String, now: Instant): TokenRow? {
        val won = jdbc.update(
            "update account_tokens set consumed_at = :now where token_hash = :h and purpose = :p and consumed_at is null and expires_at > :now",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("h", hash).addValue("p", purpose),
        ) == 1
        return if (won) find(hash) else null
    }

    override fun findOpen(purpose: String, subject: String, now: Instant): TokenRow? =
        jdbc.query(
            "select * from account_tokens where purpose = :p and subject = :s and consumed_at is null and expires_at > :now order by created_at desc limit 1",
            MapSqlParameterSource().addValue("p", purpose).addValue("s", subject).addValue("now", dialect.instantParam(now)),
        ) { rs, _ -> rs.row() }.firstOrNull()

    override fun invalidateOpen(purpose: String, subject: String, now: Instant): Int =
        jdbc.update(
            "update account_tokens set consumed_at = :now where purpose = :p and subject = :s and consumed_at is null",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("p", purpose).addValue("s", subject),
        )

    override fun purgeExpired(before: Instant): Int =
        jdbc.update("delete from account_tokens where expires_at < :b", MapSqlParameterSource().addValue("b", dialect.instantParam(before)))

    private fun ResultSet.row() = TokenRow(
        hash = getString("token_hash"), purpose = getString("purpose"), subject = getString("subject"), accountId = getString("account_id"), payload = getString("payload"),
        createdAt = dialect.readInstant(this, "created_at")!!, expiresAt = dialect.readInstant(this, "expires_at")!!, consumedAt = dialect.readInstant(this, "consumed_at"),
    )
}

/** 계정 이벤트를 `account_audit` 에 쓴다 (`skeleton.account.audit.enabled=true`). 이벤트에는 토큰 · 이메일이 없으므로 그대로 싣는다 */
class JdbcAccountAuditListener(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : AccountEventListener {
    override fun on(event: AccountEvent) {
        jdbc.update(
            "insert into account_audit (at, type, account_id, ip, detail) values (:at, :type, :account, :ip, :detail)",
            MapSqlParameterSource().addValue("at", dialect.instantParam(event.at)).addValue("type", event.type.name).addValue("account", event.accountId)
                .addValue("ip", event.ip?.take(64)).addValue("detail", event.detail.entries.joinToString(";") { "${it.key}=${it.value}" }.take(1000).ifEmpty { null }),
        )
    }
}
