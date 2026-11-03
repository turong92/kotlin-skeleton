package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.video.NewVideo
import dev.sumin.skeleton.video.TrackStatus
import dev.sumin.skeleton.video.Video
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoVisibility
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** 영상 저장 — 상태를 바꾸는 쓰기는 모두 한 문장의 조건부 갱신이라 동시에 온 호출 중 하나만 이긴다 */
class JdbcVideoStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
) : VideoStore {
    override fun insert(video: NewVideo): Video {
        jdbc.update(
            """
            insert into videos (id, owner_account_id, purpose, visibility, status, upload_key, upload_id, upload_bytes, last_upload_activity_at, created_at, updated_at)
            values (:id, :owner, :purpose, :visibility, :status, :uploadKey, :uploadId, :uploadBytes, :at, :at, :at)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", video.id)
                .addValue("owner", video.ownerAccountId)
                .addValue("purpose", video.purpose)
                .addValue("visibility", video.visibility.name)
                .addValue("status", VideoStatus.UPLOADING.name)
                .addValue("uploadKey", video.uploadKey)
                .addValue("uploadId", video.uploadId)
                .addValue("uploadBytes", video.uploadBytes)
                .addValue("at", dialect.instantParam(video.at)),
        )
        return checkNotNull(find(video.id)) { "inserted video not found" }
    }

    override fun find(id: String): Video? =
        jdbc.query("select * from videos where id = :id", mapOf("id" to id)) { rs, _ -> rs.toVideo() }.firstOrNull()

    override fun listByOwner(ownerAccountId: String, purpose: String?): List<Video> =
        jdbc.query(
            """
            select * from videos where owner_account_id = :owner and status <> 'DELETED' ${if (purpose != null) "and purpose = :purpose" else ""}
            order by created_at desc, id desc limit $LIST_LIMIT
            """.trimIndent(),
            MapSqlParameterSource().addValue("owner", ownerAccountId).addValue("purpose", purpose),
        ) { rs, _ -> rs.toVideo() }

    override fun countInFlight(ownerAccountId: String): Int =
        jdbc.queryForObject(
            "select count(*) from videos where owner_account_id = :owner and status in ('UPLOADING', 'UPLOADED', 'QUEUED', 'REMUXING')",
            mapOf("owner" to ownerAccountId),
            Int::class.java,
        )!!

    override fun touchUpload(id: String, at: Instant) {
        jdbc.update(
            "update videos set last_upload_activity_at = :at where id = :id and status = 'UPLOADING'",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id),
        )
    }

    override fun advance(id: String, from: VideoStatus, to: VideoStatus, at: Instant): Boolean =
        jdbc.update(
            "update videos set status = :to, updated_at = :at${if (to == VideoStatus.QUEUED) ", upload_id = null" else ""} where id = :id and status = :from",
            MapSqlParameterSource().addValue("to", to.name).addValue("from", from.name).addValue("at", dialect.instantParam(at)).addValue("id", id),
        ) == 1

    override fun markDeleted(id: String, at: Instant): Boolean =
        jdbc.update(
            "update videos set status = 'DELETED', updated_at = :at where id = :id and status <> 'DELETED'",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id),
        ) == 1

    override fun markUploadAbandoned(id: String, at: Instant): Boolean =
        jdbc.update(
            "update videos set status = 'FAILED', fail_code = 'UPLOAD_ABANDONED', updated_at = :at where id = :id and status in ('UPLOADING', 'UPLOADED')",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id),
        ) == 1

    override fun staleUploads(before: Instant, limit: Int): List<Video> =
        jdbc.query(
            "select * from videos where status in ('UPLOADING', 'UPLOADED') and last_upload_activity_at < :before order by last_upload_activity_at, id limit :limit",
            MapSqlParameterSource().addValue("before", dialect.instantParam(before)).addValue("limit", limit),
        ) { rs, _ -> rs.toVideo() }

    private fun ResultSet.toVideo() = Video(
        id = getString("id"),
        ownerAccountId = getString("owner_account_id"),
        purpose = getString("purpose"),
        visibility = VideoVisibility.valueOf(getString("visibility")),
        status = VideoStatus.valueOf(getString("status")),
        uploadKey = getString("upload_key"),
        uploadId = getString("upload_id"),
        uploadBytes = getLong("upload_bytes"),
        lastUploadActivityAt = dialect.readInstant(this, "last_upload_activity_at")!!,
        srcContainer = getString("src_container"),
        srcCodec = getString("src_codec"),
        srcCodecString = getString("src_codec_string"),
        srcWidth = int("src_width"),
        srcHeight = int("src_height"),
        srcRotation = int("src_rotation"),
        srcDurationMs = long("src_duration_ms"),
        srcFps = getBigDecimal("src_fps")?.toDouble(),
        srcBitrate = long("src_bitrate"),
        srcAudioCodec = getString("src_audio_codec"),
        origKey = getString("orig_key"),
        origBytes = long("orig_bytes"),
        origSha256 = getString("orig_sha256"),
        compatStatus = TrackStatus.valueOf(getString("compat_status")),
        compatKey = getString("compat_key"),
        compatBytes = long("compat_bytes"),
        ladderStatus = TrackStatus.valueOf(getString("ladder_status")),
        ladderBuild = getString("ladder_build"),
        ladderBytes = long("ladder_bytes"),
        ladderRungs = getString("ladder_rungs"),
        posterKey = getString("poster_key"),
        posterWidth = int("poster_w"),
        posterHeight = int("poster_h"),
        progress = int("progress"),
        attempts = getInt("attempts"),
        leaseOwner = getString("lease_owner"),
        leaseUntil = dialect.readInstant(this, "lease_until"),
        lane = getString("lane")?.let(VideoLane::valueOf),
        failCode = getString("fail_code"),
        failDetail = getString("fail_detail"),
        createdAt = dialect.readInstant(this, "created_at")!!,
        updatedAt = dialect.readInstant(this, "updated_at")!!,
    )

    private fun ResultSet.int(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun ResultSet.long(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private companion object {
        const val LIST_LIMIT = 200
    }
}
