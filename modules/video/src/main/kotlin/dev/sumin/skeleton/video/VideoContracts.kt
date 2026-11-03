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

    /** `UPLOADING` · `UPLOADED` 를 `FAILED(UPLOAD_ABANDONED)` 로 */
    fun markUploadAbandoned(id: String, at: Instant): Boolean

    /** 마지막 움직임이 [before] 보다 오래된 `UPLOADING` · `UPLOADED` — 오래된 것부터 [limit] 개 */
    fun staleUploads(before: Instant, limit: Int): List<Video>
}

/** 일꾼 토큰 저장 포트 (표 `video_workers`) — 일꾼 API 가 들어오는 다음 조각에서 메서드가 생긴다. `video.enabled` 이면 구현이 있어야 기동한다 */
interface VideoWorkerStore

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
