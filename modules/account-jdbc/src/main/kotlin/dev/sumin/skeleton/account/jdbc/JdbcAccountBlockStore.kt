package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.AccountBlock
import dev.sumin.skeleton.account.AccountBlockPage
import dev.sumin.skeleton.account.AccountBlockStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 재가입 차단 표 `account_blocks` — 해시만 담는다. 같은 해시를 다시 넣으면 유니크 위반을 삼켜 한 줄로 둔다 (멱등) */
class JdbcAccountBlockStore(private val jdbc: NamedParameterJdbcTemplate, private val dialect: SqlDialect) : AccountBlockStore {
    override fun add(kind: String, hash: String, reason: String?, now: Instant, expiresAt: Instant?, createdBy: String?, accountId: String?) {
        try {
            jdbc.update(
                "insert into account_blocks (kind, hash, reason, created_at, expires_at, created_by, account_id) values (:kind, :hash, :reason, :created, :expires, :by, :account)",
                MapSqlParameterSource().addValue("kind", kind).addValue("hash", hash).addValue("reason", reason).addValue("created", dialect.instantParam(now))
                    .addValue("expires", dialect.instantParam(expiresAt)).addValue("by", createdBy).addValue("account", accountId),
            )
        } catch (_: DuplicateKeyException) {
            // 이미 같은 해시가 막고 있다
        }
    }

    override fun anyActive(hashes: Collection<String>, now: Instant): Boolean =
        hashes.isNotEmpty() && (jdbc.queryForObject(
            "select count(*) from account_blocks where hash in (:hashes) and (expires_at is null or expires_at > :now)",
            MapSqlParameterSource().addValue("hashes", hashes).addValue("now", dialect.instantParam(now)), Int::class.java,
        ) ?: 0) > 0

    override fun list(page: Int, size: Int): AccountBlockPage {
        val total = jdbc.queryForObject("select count(*) from account_blocks", emptyMap<String, Any>(), Long::class.java) ?: 0
        val items = jdbc.query(
            "select * from account_blocks order by id desc limit :limit offset :offset",
            MapSqlParameterSource().addValue("limit", size).addValue("offset", page.toLong() * size),
        ) { rs, _ -> rs.block() }
        return AccountBlockPage(items, total)
    }

    override fun remove(id: Long): Boolean = jdbc.update("delete from account_blocks where id = :id", mapOf("id" to id)) == 1

    override fun sweepExpired(now: Instant): Int =
        jdbc.update("delete from account_blocks where expires_at is not null and expires_at <= :now", MapSqlParameterSource().addValue("now", dialect.instantParam(now)))

    private fun ResultSet.block() = AccountBlock(
        id = getLong("id"), kind = getString("kind"), hash = getString("hash"), reason = getString("reason"), createdAt = dialect.readInstant(this, "created_at")!!,
        expiresAt = dialect.readInstant(this, "expires_at"), createdBy = getString("created_by"), accountId = getString("account_id"),
    )
}
