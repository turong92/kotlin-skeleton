package dev.sumin.skeleton.video

import java.time.Instant

/**
 * 영상 저장 포트 — `video-jdbc` 가 DB 로 채운다. 상태를 바꾸는 쓰기는 모두 **조건부 갱신**(`where status = ?`)이라 동시에 온 두 호출 중 하나만 이기고,
 * 진 쪽은 `false` 를 받는다([VideoStatus.next] 의 표를 DB 에서 지킨다).
 */
interface VideoStore {
    fun insert(video: NewVideo): Video

    fun find(id: String): Video?

    /** 내 영상 — 지운 것은 빼고 새것부터. [purpose] 가 있으면 그 용도만 */
    fun listByOwner(ownerAccountId: String, purpose: String?): List<Video>

    /** 올리는 중부터 변환 중까지(UPLOADING · UPLOADED · QUEUED · REMUXING) — 한 계정이 동시에 진행하는 수 */
    fun countInFlight(ownerAccountId: String): Int

    /** 마지막 올리기 움직임을 [at] 으로 — 조각 주소를 받거나 조각 목록을 읽을 때 */
    fun touchUpload(id: String, at: Instant)

    /** [from] 일 때만 [to] 로. `QUEUED` 가 되면 멀티파트 id 를 비운다(끝난 멀티파트) */
    fun advance(id: String, from: VideoStatus, to: VideoStatus, at: Instant): Boolean

    /** 지워지지 않은 줄을 `DELETED` 로 */
    fun markDeleted(id: String, at: Instant): Boolean

    /** `PLAYABLE` 영상의 포스터를 바꾼다(null 이면 포스터 없음) - 지워졌거나 아직 재생 전이면 false */
    fun replacePoster(id: String, key: String?, width: Int?, height: Int?, at: Instant): Boolean

    /** `UPLOADING` · `UPLOADED` 를 `FAILED(UPLOAD_ABANDONED)` 로 */
    fun markUploadAbandoned(id: String, at: Instant): Boolean

    /** 마지막 움직임이 [before] 보다 오래된 `UPLOADING` · `UPLOADED` — 오래된 것부터 [limit] 개 */
    fun staleUploads(before: Instant, limit: Int): List<Video>

    // ---------- 일꾼(변환) — 임대 · 결과 반영. 전부 조건부 갱신이라 먼저 끝낸 쪽이 이기고 늦은 쪽은 false / null ----------

    /**
     * [lane] 의 다음 일 한 건을 집는다 — 두 일꾼이 같은 줄을 못 갖는다(`FOR UPDATE SKIP LOCKED`). 집으면 임대 · 시도 +1 · 차선을 적는다.
     * 호환 · 사다리는 영상이 `PLAYABLE` 이고 다른 차선이 임대를 쥐고 있지 않을 때만. 사다리는 [ladderBuild] 를 폴더 id 로 적는다. 없으면 null
     */
    fun claimNext(lane: VideoLane, owner: String, leaseUntil: Instant, ladderBuild: String, at: Instant): Video?

    /** 임대 소유자만 임대를 늘린다(+ [progress] 가 있으면 진행률). 임대가 거둬졌으면 false */
    fun extendLease(id: String, owner: String, until: Instant, progress: Int?, at: Instant): Boolean

    /** `REMUXING` → `PLAYABLE` — 임대 소유자만, 한 번만. 원본 단 · 포스터 · 트랙 시작 상태를 한꺼번에 적고 임대를 비운다 */
    fun markPlayable(id: String, owner: String, result: PlayableResult, at: Instant): Boolean

    /** 호환 단 `RUNNING` → `READY` — 임대 소유자만, 한 번만 */
    fun markCompatReady(id: String, owner: String, key: String, bytes: Long, at: Instant): Boolean

    /** 사다리 `RUNNING` → `READY` — [rungsJson] 은 판 목록 */
    fun markLadderReady(id: String, owner: String, build: String, bytes: Long, rungsJson: String, at: Instant): Boolean

    /**
     * 일꾼이 낸 실패 — 임대 소유자만. [retry] 이고 시도가 남았으면 다시 줄 세우고, 아니면 remux 는 영상 `FAILED`, 호환 · 사다리는 트랙만 `FAILED`.
     * 임대 소유자가 아니면 null
     */
    fun failLeased(id: String, owner: String, code: String, detail: String?, retry: Boolean, at: Instant): LeaseEnd?

    /**
     * 임대가 끝난 일을 되돌린다 — 시도가 [VideoStatus.MAX_ATTEMPTS] 에 닿았으면 다시 줄 세우지 않고 실패(remux 는 영상 `FAILED`, 트랙은 트랙만 `FAILED` 이고
     * 영상은 `PLAYABLE` 그대로). 그 밖엔 대기로. 되돌린 줄 수와 끝내 실패한 줄을 돌려준다
     */
    fun expireLeases(now: Instant, at: Instant): LeaseExpiry

    /** 올린 원 파일을 지운 뒤 키를 비운다 — `PLAYABLE` 인 영상만. 비웠으면 true */
    fun clearUploadKey(id: String, at: Instant): Boolean

    /** 사다리 `NONE`(스위치가 꺼진 동안 만들어진 영상)을 `QUEUED` 로 — 짧은 변이 [floor] 보다 큰 재생 가능 영상만. 줄 세운 수 */
    fun queueDeferredLadders(floor: Int, at: Instant): Int

    /** remux 를 기다리는(`QUEUED`) 영상 수 — 일꾼 부재 경보가 본다 */
    fun countQueuedRemux(): Int

    /** 지금 일꾼이 집을 수 있는 호환 · 사다리 대기 수 — 깨우기가 이 수가 0 이면 부르지 않는다 */
    fun countClaimableTracks(): Int

    /** 호환 단이 [before] 이전부터 대기 중인 영상 수 */
    fun countCompatQueuedBefore(before: Instant): Int

    /** [since] 이후 실패로 닫힌(영상 · 호환 · 사다리) 영상 수 */
    fun countFailedSince(since: Instant): Int
}

/** 일꾼이 낸 실패의 결과 — 다시 줄 섰나, 끝내 실패했나 */
enum class LeaseEnd { REQUEUED, FAILED }

/** 임대 만료 처리 결과 — [requeued] 는 다시 줄 선 수, [failed] 는 시도를 다 써서 끝내 실패한 줄 */
data class LeaseExpiry(val requeued: Int, val failed: List<ExpiredFailure>) {
    val total: Int get() = requeued + failed.size
}

/** [remux] 면 영상이 `FAILED`, 아니면 호환 · 사다리 트랙만 `FAILED` */
data class ExpiredFailure(val id: String, val remux: Boolean)

/** `REMUXING` → `PLAYABLE` 때 한꺼번에 적는 칸 — 일꾼의 probe 결과 · 원본 단 · 포스터 · 두 트랙의 시작 상태 */
data class PlayableResult(
    val origKey: String,
    val origBytes: Long,
    val origSha256: String,
    val srcContainer: String?,
    val srcCodec: String?,
    val srcCodecString: String?,
    val srcWidth: Int,
    val srcHeight: Int,
    val srcRotation: Int?,
    val srcDurationMs: Long,
    val srcFps: Double?,
    val srcBitrate: Long?,
    val srcAudioCodec: String?,
    val posterKey: String?,
    val posterWidth: Int?,
    val posterHeight: Int?,
    val compat: TrackStatus,
    val ladder: TrackStatus,
)

/** 일꾼 토큰 저장 포트 (표 `video_workers`) — 토큰은 SHA-256 16진수만 든다. `video.enabled` 이면 구현이 있어야 기동한다 */
interface VideoWorkerStore {
    fun insert(name: String, lanes: Set<VideoLane>, tokenSha256: String, at: Instant): VideoWorker

    /** 폐기되지 않은 일꾼만 */
    fun findActiveByTokenSha256(tokenSha256: String): VideoWorker?

    /** 폐기한 것까지 — 기동 등록이 폐기한 일꾼을 되살리지 않게 */
    fun existsByTokenSha256(tokenSha256: String): Boolean

    fun touch(id: Long, at: Instant)

    /** 폐기 — 행은 남긴다. 이미 폐기했으면 false */
    fun revoke(id: Long, at: Instant): Boolean

    fun list(): List<VideoWorker>

    /** remux 차선을 맡는 폐기되지 않은 일꾼이 마지막으로 보인 시각 — 없으면 null */
    fun lastSeenRemux(): Instant?
}

/** 누가 올리고 · 보고 · 다룰 수 있는가 — 도메인이 정한다. 기본은 [OwnerVideoAccessPolicy](올린 계정만, 공개 영상 재생은 누구나) */
interface VideoAccessPolicy {
    fun canUpload(caller: String, purpose: String, visibility: VideoVisibility): Boolean

    /** [caller] 가 null 이면 로그인하지 않은 사람 */
    fun canPlay(caller: String?, video: Video): Boolean

    /** 조각 목록 · 마침 · 상태 보기 · 지우기 */
    fun canManage(caller: String, video: Video): Boolean
}

object OwnerVideoAccessPolicy : VideoAccessPolicy {
    override fun canUpload(caller: String, purpose: String, visibility: VideoVisibility) = true

    override fun canPlay(caller: String?, video: Video) = video.visibility == VideoVisibility.PUBLIC || (caller != null && caller == video.ownerAccountId)

    override fun canManage(caller: String, video: Video) = caller == video.ownerAccountId
}

/** 올리기 전 용량 판정 — 앱이 자기 규칙(요금제 · 남은 용량)으로 채운다. 기본은 늘 허용. `false` 면 413 `VIDEO.QUOTA` */
fun interface VideoQuota {
    fun allow(caller: String, bytes: Long, purpose: String): Boolean
}
