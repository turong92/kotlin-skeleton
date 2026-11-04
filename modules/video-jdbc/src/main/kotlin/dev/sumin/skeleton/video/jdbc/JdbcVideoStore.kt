package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.persistence.jdbc.SqlDialect
import dev.sumin.skeleton.video.ExpiredFailure
import dev.sumin.skeleton.video.LeaseEnd
import dev.sumin.skeleton.video.LeaseExpiry
import dev.sumin.skeleton.video.NewVideo
import dev.sumin.skeleton.video.PlayableResult
import dev.sumin.skeleton.video.TrackStatus
import dev.sumin.skeleton.video.Video
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoVisibility
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** 영상 저장 — 상태를 바꾸는 쓰기는 모두 한 문장의 조건부 갱신이라 동시에 온 호출 중 하나만 이긴다 */
class JdbcVideoStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dialect: SqlDialect,
    private val transactions: TransactionTemplate,
) : VideoStore {
    /**
     * 집기 전용 트랜잭션 - READ COMMITTED. MySQL 기본(REPEATABLE READ)은 `FOR UPDATE` 가 훑은 인덱스 틈까지 잠가
     * 동시에 집는 일꾼끼리 갱신에서 교착이 난다. READ COMMITTED 는 틈 잠금이 없어 SKIP LOCKED 가 의도대로 줄 단위로만 건너뛴다(PostgreSQL 은 원래 기본값).
     */
    private val claimTransaction = TransactionTemplate(transactions.transactionManager!!, transactions).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

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
            // 일꾼이 쥔 임대도 함께 끊는다 — 지운 영상에 일꾼의 heartbeat · 결과가 더는 받아들여지지 않는다
            "update videos set status = 'DELETED', $RELEASE where id = :id and status <> 'DELETED'",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id),
        ) == 1

    override fun replacePoster(id: String, key: String?, width: Int?, height: Int?, at: Instant): Boolean =
        jdbc.update(
            "update videos set poster_key = :key, poster_w = :w, poster_h = :h, updated_at = :at where id = :id and status = 'PLAYABLE'",
            MapSqlParameterSource().addValue("key", key).addValue("w", width).addValue("h", height).addValue("at", dialect.instantParam(at)).addValue("id", id),
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

    // ---------- 일꾼(변환) ----------

    override fun claimNext(lane: VideoLane, owner: String, leaseUntil: Instant, ladderBuild: String, at: Instant): Video? = claimTransaction.execute {
        // 한 트랜잭션에서 잠그고(SKIP LOCKED - 다른 일꾼이 잡은 줄은 건너뛴다) 갱신한다. 두 방언 모두 같은 문법이다
        val id = jdbc.query("select id from videos where ${candidate(lane)} order by created_at, id limit 1 for update skip locked", emptyMap<String, Any>()) { rs, _ -> rs.getString(1) }
            .firstOrNull() ?: return@execute null
        val set = when (lane) {
            VideoLane.REMUX -> "status = 'REMUXING'"
            VideoLane.COMPAT -> "compat_status = 'RUNNING'"
            VideoLane.LADDER -> "ladder_status = 'RUNNING', ladder_build = :build"
            VideoLane.ANIMATED -> error("not a video lane")
        }
        val claimed = jdbc.update(
            "update videos set lease_owner = :owner, lease_until = :until, lane = :lane, attempts = attempts + 1, progress = null, updated_at = :at, $set where id = :id and ${candidate(lane)}",
            MapSqlParameterSource().addValue("owner", owner).addValue("until", dialect.instantParam(leaseUntil)).addValue("lane", lane.name)
                .addValue("at", dialect.instantParam(at)).addValue("build", ladderBuild).addValue("id", id),
        )
        if (claimed == 1) find(id) else null
    }

    private fun candidate(lane: VideoLane) = when (lane) {
        VideoLane.REMUX -> "status = 'QUEUED'"
        VideoLane.COMPAT -> "status = 'PLAYABLE' and compat_status = 'QUEUED' and lease_owner is null"
        VideoLane.LADDER -> "status = 'PLAYABLE' and ladder_status = 'QUEUED' and lease_owner is null"
        VideoLane.ANIMATED -> error("not a video lane")
    }

    override fun extendLease(id: String, owner: String, until: Instant, progress: Int?, at: Instant): Boolean =
        jdbc.update(
            "update videos set lease_until = :until, updated_at = :at${if (progress != null) ", progress = :progress" else ""} where id = :id and lease_owner = :owner and lease_until is not null",
            MapSqlParameterSource().addValue("until", dialect.instantParam(until)).addValue("at", dialect.instantParam(at))
                .addValue("progress", progress).addValue("id", id).addValue("owner", owner),
        ) == 1

    override fun markPlayable(id: String, owner: String, result: PlayableResult, at: Instant): Boolean =
        jdbc.update(
            """
            update videos set status = 'PLAYABLE', orig_key = :origKey, orig_bytes = :origBytes, orig_sha256 = :origSha, src_container = :container, src_codec = :codec,
                src_codec_string = :codecString, src_width = :w, src_height = :h, src_rotation = :rotation, src_duration_ms = :durationMs, src_fps = :fps, src_bitrate = :bitrate,
                src_audio_codec = :audio, poster_key = :posterKey, poster_w = :posterW, poster_h = :posterH, compat_status = :compat, compat_key = :compatKey, ladder_status = :ladder,
                attempts = 0, lease_owner = null, lease_until = null, lane = null, progress = null, updated_at = :at
            where id = :id and status = 'REMUXING' and lease_owner = :owner
            """.trimIndent(),
            MapSqlParameterSource().addValue("origKey", result.origKey).addValue("origBytes", result.origBytes).addValue("origSha", result.origSha256)
                .addValue("container", result.srcContainer).addValue("codec", result.srcCodec).addValue("codecString", result.srcCodecString)
                .addValue("w", result.srcWidth).addValue("h", result.srcHeight).addValue("rotation", result.srcRotation).addValue("durationMs", result.srcDurationMs)
                .addValue("fps", result.srcFps?.let { BigDecimal.valueOf(it).setScale(3, RoundingMode.HALF_UP) }).addValue("bitrate", result.srcBitrate)
                .addValue("audio", result.srcAudioCodec).addValue("posterKey", result.posterKey).addValue("posterW", result.posterWidth).addValue("posterH", result.posterHeight)
                .addValue("compat", result.compat.name).addValue("compatKey", if (result.compat == TrackStatus.SKIPPED) result.origKey else null)
                .addValue("ladder", result.ladder.name).addValue("at", dialect.instantParam(at)).addValue("id", id).addValue("owner", owner),
        ) == 1

    override fun markCompatReady(id: String, owner: String, key: String, bytes: Long, at: Instant): Boolean =
        jdbc.update(
            "update videos set compat_status = 'READY', compat_key = :key, compat_bytes = :bytes, attempts = 0, $RELEASE where id = :id and status = 'PLAYABLE' and compat_status = 'RUNNING' and lease_owner = :owner",
            MapSqlParameterSource().addValue("key", key).addValue("bytes", bytes).addValue("at", dialect.instantParam(at)).addValue("id", id).addValue("owner", owner),
        ) == 1

    override fun markLadderReady(id: String, owner: String, build: String, bytes: Long, rungsJson: String, at: Instant): Boolean =
        jdbc.update(
            "update videos set ladder_status = 'READY', ladder_build = :build, ladder_bytes = :bytes, ladder_rungs = :rungs, attempts = 0, $RELEASE where id = :id and status = 'PLAYABLE' and ladder_status = 'RUNNING' and lease_owner = :owner",
            MapSqlParameterSource().addValue("build", build).addValue("bytes", bytes).addValue("rungs", rungsJson)
                .addValue("at", dialect.instantParam(at)).addValue("id", id).addValue("owner", owner),
        ) == 1

    override fun failLeased(id: String, owner: String, code: String, detail: String?, retry: Boolean, at: Instant): LeaseEnd? {
        val row = jdbc.query("select lease_owner, lane, attempts from videos where id = :id", mapOf("id" to id)) { rs, _ ->
            Triple(rs.getString("lease_owner"), rs.getString("lane")?.let(VideoLane::valueOf), rs.getInt("attempts"))
        }.firstOrNull() ?: return null
        val lane = row.second
        if (row.first != owner || lane == null) return null
        val requeue = retry && row.third < VideoStatus.MAX_ATTEMPTS
        val column = when (lane) { VideoLane.REMUX -> "status"; VideoLane.COMPAT -> "compat_status"; VideoLane.LADDER -> "ladder_status"; VideoLane.ANIMATED -> error("not a video lane") }
        val set = when {
            requeue -> "$column = 'QUEUED'"
            lane == VideoLane.REMUX -> "status = 'FAILED', fail_code = :code, fail_detail = :detail"
            else -> "$column = 'FAILED', fail_code = :code, fail_detail = :detail, attempts = 0"
        }
        val updated = jdbc.update(
            "update videos set $RELEASE, $set where id = :id and lease_owner = :owner and lane = :lane",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("code", code).addValue("detail", detail)
                .addValue("id", id).addValue("owner", owner).addValue("lane", lane.name),
        )
        return if (updated != 1) null else if (requeue) LeaseEnd.REQUEUED else LeaseEnd.FAILED
    }

    override fun expireLeases(now: Instant, at: Instant): LeaseExpiry {
        val params = { MapSqlParameterSource().addValue("now", dialect.instantParam(now)).addValue("at", dialect.instantParam(at)).addValue("max", VideoStatus.MAX_ATTEMPTS) }
        val failed = mutableListOf<ExpiredFailure>()
        var requeued = 0
        // 영상(remux) — 시도를 다 썼으면 실패, 아니면 다시 줄
        val remuxExpired = "status = 'REMUXING' and lease_until < :now"
        failed += failExpired("$remuxExpired and attempts >= :max", "status = 'FAILED', fail_code = 'LEASE_EXPIRED'", params, remux = true)
        requeued += jdbc.update("update videos set $RELEASE, status = 'QUEUED' where $remuxExpired and attempts < :max", params())
        // 트랙 — 트랙만 실패 · 다시 줄. 영상(PLAYABLE)과 원본 단은 그대로. 시도 칸은 차선 공용이라 닫힌 트랙이 다음 트랙에 남은 시도를 물려주지 않게 0 으로
        for (column in listOf("compat_status", "ladder_status")) {
            val expired = "status = 'PLAYABLE' and $column = 'RUNNING' and lease_until < :now"
            failed += failExpired("$expired and attempts >= :max", "$column = 'FAILED', fail_code = 'LEASE_EXPIRED', attempts = 0", params, remux = false)
            requeued += jdbc.update("update videos set $RELEASE, $column = 'QUEUED' where $expired and attempts < :max", params())
        }
        return LeaseExpiry(requeued, failed)
    }

    /** 줄마다 같은 조건을 다시 달아 갱신한다 — 갱신이 1 을 돌려준 줄만 센다(동시에 다른 호출이 거둔 줄을 두 번 세지 않는다) */
    private fun failExpired(where: String, set: String, params: () -> MapSqlParameterSource, remux: Boolean): List<ExpiredFailure> =
        jdbc.query("select id from videos where $where", params()) { rs, _ -> rs.getString(1) }
            .filter { id -> jdbc.update("update videos set $RELEASE, $set where id = :id and $where", params().addValue("id", id)) == 1 }
            .map { ExpiredFailure(it, remux) }

    override fun clearUploadKey(id: String, at: Instant): Boolean =
        jdbc.update(
            "update videos set upload_key = null, updated_at = :at where id = :id and status = 'PLAYABLE' and upload_key is not null",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("id", id),
        ) == 1

    override fun queueDeferredLadders(floor: Int, at: Instant): Int =
        jdbc.update(
            "update videos set ladder_status = 'QUEUED', updated_at = :at where status = 'PLAYABLE' and ladder_status = 'NONE' and least(src_width, src_height) > :floor",
            MapSqlParameterSource().addValue("at", dialect.instantParam(at)).addValue("floor", floor),
        )

    override fun countQueuedRemux(): Int = count("select count(*) from videos where status = 'QUEUED'")

    override fun countClaimableTracks(): Int =
        count("select count(*) from videos where status = 'PLAYABLE' and lease_owner is null and (compat_status = 'QUEUED' or ladder_status = 'QUEUED')")

    override fun countCompatQueuedBefore(before: Instant): Int =
        count("select count(*) from videos where status = 'PLAYABLE' and compat_status = 'QUEUED' and updated_at < :t", before)

    override fun countFailedSince(since: Instant): Int =
        count("select count(*) from videos where updated_at >= :t and status <> 'DELETED' and (status = 'FAILED' or compat_status = 'FAILED' or ladder_status = 'FAILED')", since)

    private fun count(sql: String, t: Instant? = null): Int =
        jdbc.queryForObject(sql, MapSqlParameterSource().also { if (t != null) it.addValue("t", dialect.instantParam(t)) }, Int::class.java)!!

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

        /** 임대를 비우는 갱신 조각 — 진행률도 함께 비운다 */
        const val RELEASE = "lease_owner = null, lease_until = null, lane = null, progress = null, updated_at = :at"
    }
}
