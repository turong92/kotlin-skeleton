package dev.sumin.skeleton.video

import java.time.Instant

/** 영상마다 올릴 때 정한다 — 공개는 CDN 이 캐시하는 주소로, 비공개는 재생 허가마다 짧은 출입증을 붙여 튼다. 올린 뒤에는 바꾸지 않는다 */
enum class VideoVisibility { PUBLIC, PRIVATE }

/**
 * 주 상태. [PLAYABLE] 은 원본 단(재인코딩 없는 조각)이 올라간 순간이고 호환 · 사다리는 독립 트랙([TrackStatus])이다.
 * 전이는 순수 함수 [next] 가 표로 정하고, 저장소의 조건부 갱신(`update … where status = ?`)이 그 표를 DB 에서 지킨다.
 */
enum class VideoStatus {
    UPLOADING, UPLOADED, QUEUED, REMUXING, PLAYABLE, FAILED, DELETED;

    /** [event] 뒤의 상태. 표에 없으면 null. [attempts] 는 지금까지 집힌 횟수 — 임대 만료는 [MAX_ATTEMPTS] 에 닿으면 다시 줄 세우지 않고 실패다 */
    fun next(event: VideoEvent, attempts: Int = 0): VideoStatus? = when (event) {
        VideoEvent.COMPLETE -> UPLOADED.takeIf { this == UPLOADING }
        VideoEvent.VERIFIED -> QUEUED.takeIf { this == UPLOADED }
        VideoEvent.CLAIM -> REMUXING.takeIf { this == QUEUED }
        VideoEvent.REMUX_DONE -> PLAYABLE.takeIf { this == REMUXING }
        VideoEvent.LEASE_EXPIRED -> if (this != REMUXING) null else if (attempts >= MAX_ATTEMPTS) FAILED else QUEUED
        VideoEvent.FAIL -> FAILED.takeIf { this == REMUXING }
        VideoEvent.DELETE -> DELETED.takeIf { this != DELETED }
    }

    companion object {
        /** 시도 최대 3 */
        const val MAX_ATTEMPTS = 3
    }
}

enum class VideoEvent { COMPLETE, VERIFIED, CLAIM, REMUX_DONE, LEASE_EXPIRED, FAIL, DELETE }

/** 호환 · 사다리 트랙 상태 — 실패해도 영상은 계속 재생된다 */
enum class TrackStatus { NONE, SKIPPED, QUEUED, RUNNING, READY, FAILED }

/**
 * 일꾼이 집는 일의 종류. [ANIMATED] 는 영상 줄이 아니라 이미지 모듈의 움직이는 GIF · WebP 표시본 변환이다(`video` 와 `storage-image` 가 둘 다 켜졌을 때만 일이 생긴다) -
 * 영상 줄([Video])의 차선이 아니므로 [Video.lane] 에는 오지 않는다
 */
enum class VideoLane { REMUX, COMPAT, LADDER, ANIMATED }

/** 영상 한 줄 — 표 `videos` 의 모양 그대로. 올리기 흐름은 앞쪽 칸만 쓰고, 나머지는 일꾼 API 가 채운다 */
data class Video(
    val id: String,
    val ownerAccountId: String,
    /** 앱이 정하는 짧은 이름(예: `note`) — 모듈은 뜻을 모른다 */
    val purpose: String,
    val visibility: VideoVisibility,
    val status: VideoStatus,
    val uploadKey: String? = null,
    val uploadId: String? = null,
    val uploadBytes: Long = 0,
    val lastUploadActivityAt: Instant,
    val srcContainer: String? = null,
    val srcCodec: String? = null,
    val srcCodecString: String? = null,
    val srcWidth: Int? = null,
    val srcHeight: Int? = null,
    val srcRotation: Int? = null,
    val srcDurationMs: Long? = null,
    val srcFps: Double? = null,
    val srcBitrate: Long? = null,
    val srcAudioCodec: String? = null,
    val origKey: String? = null,
    val origBytes: Long? = null,
    val origSha256: String? = null,
    val compatStatus: TrackStatus = TrackStatus.NONE,
    val compatKey: String? = null,
    val compatBytes: Long? = null,
    val ladderStatus: TrackStatus = TrackStatus.NONE,
    val ladderBuild: String? = null,
    val ladderBytes: Long? = null,
    val ladderRungs: String? = null,
    val posterKey: String? = null,
    val posterWidth: Int? = null,
    val posterHeight: Int? = null,
    val progress: Int? = null,
    val attempts: Int = 0,
    val leaseOwner: String? = null,
    val leaseUntil: Instant? = null,
    val lane: VideoLane? = null,
    val failCode: String? = null,
    val failDetail: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 올리기를 시작한 영상 — 멀티파트가 이미 열렸다 */
data class NewVideo(
    val id: String,
    val ownerAccountId: String,
    val purpose: String,
    val visibility: VideoVisibility,
    val uploadKey: String,
    val uploadId: String,
    val uploadBytes: Long,
    val at: Instant,
)
