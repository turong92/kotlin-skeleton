package dev.sumin.skeleton.apikey.jdbc

import dev.sumin.skeleton.apikey.ApiKey
import dev.sumin.skeleton.apikey.ApiKeyStore
import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * 표 하나(`api_keys`). 상태 전이는 모두 한 문장의 조건부 갱신이라 동시에 와도 한쪽만 이긴다 -
 * 회수는 `revoked_at is null`, 마지막 사용은 `last_used_at is null or last_used_at < :stale_before`(1분 해상도 - 같은 키의 요청이 몰려도 쓰기는 분당 한 번).
 * 조회는 `token_hash` 유니크 색인 · `(owner_type, owner_id, created_at)` 색인을 탄다.
 */
class JdbcApiKeyStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : ApiKeyStore {
    override fun insert(key: ApiKey) {
        jdbc.update(
            "insert into api_keys (id, owner_type, owner_id, created_by, name, token_hash, token_hint, scopes, expires_at, created_at) " +
                "values (:id, :owner_type, :owner_id, :created_by, :name, :token_hash, :token_hint, :scopes, :expires_at, :created_at)",
            MapSqlParameterSource()
                .addValue("id", key.id)
                .addValue("owner_type", key.ownerType)
                .addValue("owner_id", key.ownerId)
                .addValue("created_by", key.createdBy)
                .addValue("name", key.name)
                .addValue("token_hash", key.tokenHash)
                .addValue("token_hint", key.tokenHint)
                .addValue("scopes", key.scopes.sorted().joinToString(" "))
                .addValue("expires_at", instant(key.expiresAt))
                .addValue("created_at", instant(key.createdAt)),
        )
    }

    override fun findById(id: String): ApiKey? =
        jdbc.query("select * from api_keys where id = :id", mapOf("id" to id)) { rs, _ -> rs.toKey() }.firstOrNull()

    override fun findByTokenHash(hash: String): ApiKey? =
        jdbc.query("select * from api_keys where token_hash = :hash", mapOf("hash" to hash)) { rs, _ -> rs.toKey() }.firstOrNull()

    override fun listByOwner(ownerType: String, ownerId: String): List<ApiKey> =
        jdbc.query(
            "select * from api_keys where owner_type = :t and owner_id = :o order by created_at desc, id desc",
            mapOf("t" to ownerType, "o" to ownerId),
        ) { rs, _ -> rs.toKey() }

    override fun countActive(ownerType: String, ownerId: String, now: Instant): Int =
        jdbc.queryForObject(
            "select count(*) from api_keys where owner_type = :t and owner_id = :o and revoked_at is null and (expires_at is null or expires_at > :now)",
            MapSqlParameterSource().addValue("t", ownerType).addValue("o", ownerId).addValue("now", instant(now)),
            Int::class.java,
        )!!

    override fun revoke(id: String, by: String, reason: String?, now: Instant): Boolean =
        jdbc.update(
            "update api_keys set revoked_at = :now, revoked_by = :by, revoked_reason = :reason where id = :id and revoked_at is null",
            MapSqlParameterSource().addValue("id", id).addValue("by", by).addValue("reason", reason, Types.VARCHAR).addValue("now", instant(now)),
        ) == 1

    override fun touchLastUsed(id: String, now: Instant, ip: String?, staleBefore: Instant): Boolean =
        jdbc.update(
            "update api_keys set last_used_at = :now, last_used_ip = :ip where id = :id and (last_used_at is null or last_used_at < :stale_before)",
            MapSqlParameterSource().addValue("id", id).addValue("ip", ip, Types.VARCHAR).addValue("now", instant(now)).addValue("stale_before", instant(staleBefore)),
        ) == 1

    override fun deleteByOwner(ownerType: String, ownerId: String): Int =
        jdbc.update("delete from api_keys where owner_type = :t and owner_id = :o", mapOf("t" to ownerType, "o" to ownerId))

    private fun instant(value: Instant?): Any? = dialect.instantParam(value)

    private fun ResultSet.toKey() = ApiKey(
        id = getString("id"),
        ownerType = getString("owner_type"),
        ownerId = getString("owner_id"),
        createdBy = getString("created_by"),
        name = getString("name"),
        tokenHash = getString("token_hash"),
        tokenHint = getString("token_hint"),
        scopes = getString("scopes").split(' ').filter { it.isNotEmpty() }.toSet(),
        expiresAt = dialect.readInstant(this, "expires_at"),
        revokedAt = dialect.readInstant(this, "revoked_at"),
        revokedBy = getString("revoked_by"),
        revokedReason = getString("revoked_reason"),
        lastUsedAt = dialect.readInstant(this, "last_used_at"),
        lastUsedIp = getString("last_used_ip"),
        createdAt = dialect.readInstant(this, "created_at")!!,
    )
}
