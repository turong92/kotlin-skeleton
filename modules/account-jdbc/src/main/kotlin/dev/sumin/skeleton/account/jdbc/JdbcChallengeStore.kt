package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.challenge.ChallengeRow
import dev.sumin.skeleton.account.challenge.ChallengeStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 코드 챌린지 저장소 — 시도 차감 · 소비 · 코드 교체는 **조건부 UPDATE/DELETE 한 문장**이라 두 DB 모두에서 동시 요청이 시도 수를 넘기지 못하고 소비는 하나만 이긴다.
 * (주인 열은 MySQL 에서 이진 정렬이라 악센트 · 대소문자만 다른 주소가 서로의 시도를 지우지 않는다)
 */
class JdbcChallengeStore(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : ChallengeStore {
    override fun insert(row: ChallengeRow) {
        jdbc.update(
            """
            insert into skeleton_account_challenges (id, purpose, subject, account_id, session_id, payload, secret, code_hash, attempts_left, resends, created_at, expires_at, last_sent_at, ip)
            values (:id, :purpose, :subject, :account, :session, :payload, :secret, :hash, :attempts, :resends, :created, :expires, :sent, :ip)
            """.trimIndent(),
            MapSqlParameterSource().addValue("id", row.id).addValue("purpose", row.purpose).addValue("subject", row.subject).addValue("account", row.accountId)
                .addValue("session", row.sessionId).addValue("payload", row.payload).addValue("secret", row.secret).addValue("hash", row.codeHash)
                .addValue("attempts", row.attemptsLeft).addValue("resends", row.resends).addValue("created", dialect.instantParam(row.createdAt))
                .addValue("expires", dialect.instantParam(row.expiresAt)).addValue("sent", dialect.instantParam(row.lastSentAt)).addValue("ip", row.ip?.take(64)),
        )
    }

    override fun find(id: String): ChallengeRow? =
        jdbc.query("select * from skeleton_account_challenges where id = :id", mapOf("id" to id)) { rs, _ -> rs.row() }.firstOrNull()

    override fun findOpen(purpose: String, subject: String, now: Instant): ChallengeRow? =
        jdbc.query(
            "select * from skeleton_account_challenges where purpose = :p and subject = :s and expires_at > :now order by created_at desc limit 1",
            MapSqlParameterSource().addValue("p", purpose).addValue("s", subject).addValue("now", dialect.instantParam(now)),
        ) { rs, _ -> rs.row() }.firstOrNull()

    override fun spendAttempt(id: String, now: Instant): ChallengeRow? {
        val spent = jdbc.update(
            "update skeleton_account_challenges set attempts_left = attempts_left - 1 where id = :id and attempts_left > 0 and expires_at > :now",
            MapSqlParameterSource().addValue("id", id).addValue("now", dialect.instantParam(now)),
        ) == 1
        return if (spent) find(id) else null
    }

    override fun delete(id: String): Boolean = jdbc.update("delete from skeleton_account_challenges where id = :id", mapOf("id" to id)) == 1

    override fun deleteBySubject(purpose: String, subject: String): Int =
        jdbc.update("delete from skeleton_account_challenges where purpose = :p and subject = :s", mapOf("p" to purpose, "s" to subject))

    override fun deleteByAccount(accountId: String, purposes: Collection<String>): Int =
        if (purposes.isEmpty()) 0 else jdbc.update("delete from skeleton_account_challenges where account_id = :a and purpose in (:purposes)", mapOf("a" to accountId, "purposes" to purposes))

    override fun replaceCode(id: String, codeHash: String, expiresAt: Instant, now: Instant, sentBefore: Instant, maxResends: Int, attempts: Int): Boolean =
        jdbc.update(
            "update skeleton_account_challenges set code_hash = :hash, expires_at = :expires, last_sent_at = :now, resends = resends + 1, attempts_left = :attempts " +
                "where id = :id and expires_at > :now and last_sent_at <= :before and resends < :max",
            MapSqlParameterSource().addValue("hash", codeHash).addValue("expires", dialect.instantParam(expiresAt)).addValue("now", dialect.instantParam(now))
                .addValue("attempts", attempts).addValue("id", id).addValue("before", dialect.instantParam(sentBefore)).addValue("max", maxResends),
        ) == 1

    override fun purgeExpired(before: Instant): Int =
        jdbc.update("delete from skeleton_account_challenges where expires_at < :b", MapSqlParameterSource().addValue("b", dialect.instantParam(before)))

    private fun ResultSet.row() = ChallengeRow(
        id = getString("id"), purpose = getString("purpose"), subject = getString("subject"), accountId = getString("account_id"), sessionId = getString("session_id"),
        payload = getString("payload"), secret = getString("secret"), codeHash = getString("code_hash"), attemptsLeft = getInt("attempts_left"), resends = getInt("resends"),
        createdAt = dialect.readInstant(this, "created_at")!!, expiresAt = dialect.readInstant(this, "expires_at")!!, lastSentAt = dialect.readInstant(this, "last_sent_at")!!,
        ip = getString("ip"),
    )
}
