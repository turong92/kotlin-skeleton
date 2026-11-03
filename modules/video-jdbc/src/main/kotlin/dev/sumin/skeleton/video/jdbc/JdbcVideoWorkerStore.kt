package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoWorker
import dev.sumin.skeleton.video.VideoWorkerStore
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder

/** `video_workers` — 토큰은 SHA-256 16진수만 든다. 폐기는 `revoked_at` 만 채운다(행은 남긴다 — `last_seen_at` 으로 일꾼 부재를 본다) */
class JdbcVideoWorkerStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : VideoWorkerStore {
    override fun insert(name: String, lanes: Set<VideoLane>, tokenSha256: String, at: Instant): VideoWorker {
        val keys = GeneratedKeyHolder()
        jdbc.update(
            "insert into video_workers (name, token_sha256, lanes, created_at) values (:name, :sha, :lanes, :at)",
            MapSqlParameterSource().addValue("name", name).addValue("sha", tokenSha256).addValue("lanes", lanes.joinToString(",") { it.name }).addValue("at", dialect.instantParam(at)),
            keys,
            arrayOf("id"),
        )
        return checkNotNull(find(keys.key!!.toLong())) { "inserted worker not found" }
    }

    override fun findActiveByTokenSha256(tokenSha256: String): VideoWorker? =
        jdbc.query("select * from video_workers where token_sha256 = :sha and revoked_at is null", mapOf("sha" to tokenSha256)) { rs, _ -> rs.toWorker() }.firstOrNull()

    override fun existsByTokenSha256(tokenSha256: String): Boolean =
        jdbc.queryForObject("select count(*) from video_workers where token_sha256 = :sha", mapOf("sha" to tokenSha256), Int::class.java)!! > 0

    override fun touch(id: Long, at: Instant) {
        jdbc.update("update video_workers set last_seen_at = :at where id = :id", MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id))
    }

    override fun revoke(id: Long, at: Instant): Boolean =
        jdbc.update("update video_workers set revoked_at = :at where id = :id and revoked_at is null", MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id)) == 1

    override fun list(): List<VideoWorker> = jdbc.query("select * from video_workers order by id", emptyMap<String, Any>()) { rs, _ -> rs.toWorker() }

    override fun lastSeenRemux(): Instant? =
        jdbc.query(
            "select max(last_seen_at) as seen from video_workers where revoked_at is null and lanes like '%REMUX%'",
            emptyMap<String, Any>(),
        ) { rs, _ -> dialect.readInstant(rs, "seen") }.firstOrNull()

    private fun find(id: Long): VideoWorker? = jdbc.query("select * from video_workers where id = :id", mapOf("id" to id)) { rs, _ -> rs.toWorker() }.firstOrNull()

    private fun ResultSet.toWorker() = VideoWorker(
        id = getLong("id"),
        name = getString("name"),
        lanes = getString("lanes").split(',').filter { it.isNotBlank() }.map(VideoLane::valueOf).toSet(),
        createdAt = dialect.readInstant(this, "created_at")!!,
        lastSeenAt = dialect.readInstant(this, "last_seen_at"),
        revokedAt = dialect.readInstant(this, "revoked_at"),
    )
}
