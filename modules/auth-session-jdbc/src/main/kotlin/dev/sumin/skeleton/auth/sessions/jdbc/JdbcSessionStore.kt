package dev.sumin.skeleton.auth.sessions.jdbc

import dev.sumin.skeleton.auth.sessions.SessionRecord
import dev.sumin.skeleton.auth.sessions.SessionStore
import dev.sumin.skeleton.auth.sessions.TokenRecord
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

/**
 * [SessionStore] 를 PostgreSQL · MySQL 로 구현한다. 한 토큰을 두 번 쓰지 못하게 하는 것은 조건부 UPDATE 하나다 —
 * `update … set used_at = :now where token_hash = :h and used_at is null` 의 갱신 행 수가 1 인 쪽만 이긴다 (두 DB 모두 같은 의미).
 */
class JdbcSessionStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val dialect: SqlDialect,
) : SessionStore {
    override fun create(session: SessionRecord, firstTokenHash: String) {
        tx.executeWithoutResult {
            jdbc.update(
                """
                insert into skeleton_auth_sessions (id, account_id, device_name, user_agent, ip, created_at, last_used_at, expires_at)
                values (:id, :account, :device, :ua, :ip, :created, :used, :expires)
                """.trimIndent(),
                MapSqlParameterSource().addValue("id", session.id).addValue("account", session.accountId).addValue("device", session.deviceName)
                    .addValue("ua", session.userAgent).addValue("ip", session.ip).addValue("created", dialect.instantParam(session.createdAt))
                    .addValue("used", dialect.instantParam(session.lastUsedAt)).addValue("expires", dialect.instantParam(session.expiresAt)),
            )
            insertToken(session.id, firstTokenHash, session.createdAt)
        }
    }

    override fun findToken(hash: String): TokenRecord? =
        jdbc.query("select session_id, used_at from skeleton_auth_refresh_tokens where token_hash = :h", mapOf("h" to hash)) { rs, _ ->
            TokenRecord(rs.getString("session_id"), dialect.readInstant(rs, "used_at"))
        }.firstOrNull()

    override fun markTokenUsed(hash: String, now: Instant): Boolean =
        jdbc.update(
            "update skeleton_auth_refresh_tokens set used_at = :now where token_hash = :h and used_at is null",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("h", hash),
        ) == 1

    override fun addToken(sessionId: String, hash: String, now: Instant) {
        try { insertToken(sessionId, hash, now) } catch (_: org.springframework.dao.DuplicateKeyException) { /* 이미 있다 — 멱등 */ }
    }

    private fun insertToken(sessionId: String, hash: String, now: Instant) {
        jdbc.update(
            "insert into skeleton_auth_refresh_tokens (token_hash, session_id, created_at) values (:h, :s, :now)",
            MapSqlParameterSource().addValue("h", hash).addValue("s", sessionId).addValue("now", dialect.instantParam(now)),
        )
    }

    override fun find(sessionId: String): SessionRecord? =
        jdbc.query("select * from skeleton_auth_sessions where id = :id", mapOf("id" to sessionId)) { rs, _ -> rs.session() }.firstOrNull()

    override fun touch(sessionId: String, now: Instant, ip: String?, userAgent: String?) {
        jdbc.update(
            "update skeleton_auth_sessions set last_used_at = :now, ip = coalesce(:ip, ip), user_agent = coalesce(:ua, user_agent) where id = :id",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("ip", ip).addValue("ua", userAgent).addValue("id", sessionId),
        )
    }

    override fun revoke(sessionId: String, now: Instant, reason: String): Boolean =
        jdbc.update(
            "update skeleton_auth_sessions set revoked_at = :now, revoked_reason = :reason where id = :id and revoked_at is null",
            MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("reason", reason).addValue("id", sessionId),
        ) == 1

    override fun revokeAll(accountId: String, exceptSessionId: String?, now: Instant, reason: String): Int {
        // `(:except is null or id <> :except)` 는 PostgreSQL 이 타입 없는 null 매개변수를 못 정해 실패한다 — 문장을 둘로 나눈다
        val p = MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("reason", reason).addValue("account", accountId)
        val sql = "update skeleton_auth_sessions set revoked_at = :now, revoked_reason = :reason where account_id = :account and revoked_at is null"
        return if (exceptSessionId == null) jdbc.update(sql, p) else jdbc.update("$sql and id <> :except", p.addValue("except", exceptSessionId))
    }

    override fun listActive(accountId: String, now: Instant, idleCutoff: Instant): List<SessionRecord> =
        jdbc.query(
            "select * from skeleton_auth_sessions where account_id = :account and revoked_at is null and expires_at > :now and last_used_at > :idle " +
                "order by created_at desc, id desc",
            MapSqlParameterSource().addValue("account", accountId).addValue("now", dialect.instantParam(now)).addValue("idle", dialect.instantParam(idleCutoff)),
        ) { rs, _ -> rs.session() }

    override fun pruneUsedTokens(sessionId: String, usedBefore: Instant) {
        jdbc.update(
            "delete from skeleton_auth_refresh_tokens where session_id = :s and used_at is not null and used_at < :before",
            MapSqlParameterSource().addValue("s", sessionId).addValue("before", dialect.instantParam(usedBefore)),
        )
    }

    override fun purge(before: Instant): Int =
        jdbc.update(
            "delete from skeleton_auth_sessions where (revoked_at is not null and revoked_at < :b) or expires_at < :b",
            MapSqlParameterSource().addValue("b", dialect.instantParam(before)),
        )

    /** 토큰은 FK `on delete cascade` 로 함께 지워진다 */
    override fun eraseAccount(accountId: String): Int = jdbc.update("delete from skeleton_auth_sessions where account_id = :a", mapOf("a" to accountId))

    private fun ResultSet.session() = SessionRecord(
        id = getString("id"), accountId = getString("account_id"), deviceName = getString("device_name"), userAgent = getString("user_agent"),
        ip = getString("ip"), createdAt = dialect.readInstant(this, "created_at")!!, lastUsedAt = dialect.readInstant(this, "last_used_at")!!,
        expiresAt = dialect.readInstant(this, "expires_at")!!, revokedAt = dialect.readInstant(this, "revoked_at"), revokedReason = getString("revoked_reason"),
    )
}
