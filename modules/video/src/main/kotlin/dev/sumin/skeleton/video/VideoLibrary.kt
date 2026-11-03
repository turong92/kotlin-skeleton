package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * 영상 한 편을 두고 하는 일 둘 — 재생 허가와 삭제. 남의 · 지운 · 없는 영상은 모두 같은 [VideoNotFoundException] 이다.
 * 삭제는 줄을 먼저 `DELETED` 로 닫고(진실의 원천 · 일꾼의 임대도 이때 끊긴다) 객체 정리는 같은 트랜잭션에서 큐에 넣은 후속(`video.finalize DELETED`)이 한다 —
 * 그래서 공개 영상의 캐시 비우기(= 큐 줄)가 폴더를 지우기 전에 끝나고, 실패하면 큐가 다시 돌린다. 되돌리기는 없다(원본 단이 곧 보관본이라 지우면 끝이다)
 */
class VideoLibrary(
    private val store: VideoStore,
    private val policy: VideoAccessPolicy,
    private val playback: VideoPlayback,
    private val followUps: VideoFollowUps,
    private val tx: VideoTransactions,
    private val time: TimeProvider,
    /** 지운 뒤 폴더 정리를 한 번 더 하는 지연 - 변환 중이던 일꾼이 서명 주소로 뒤늦게 올린 파일이 지운 폴더에 남지 않게, 일꾼의 서명 주소 수명(`worker.url-ttl`) */
    private val lateCleanupDelay: Duration,
    /** 이미지 모듈이 있으면 검사 전의 일꾼 포스터를 내주지 않는다 */
    private val posters: VideoPosters? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [viewer] 가 null 이면 로그인하지 않은 사람 — 공개 영상만 [VideoAccessPolicy.canPlay] 를 통과한다. 아직 `PLAYABLE` 이 아니면 [VideoNotReadyException] */
    fun playback(viewer: String?, videoId: String): PlaybackGrant {
        val video = store.find(videoId)?.takeIf { it.status != VideoStatus.DELETED && policy.canPlay(viewer, it) } ?: throw VideoNotFoundException()
        if (video.status != VideoStatus.PLAYABLE) throw VideoNotReadyException(video.status)
        val grant = playback.grant(video, viewer)
        return if (posters?.hides(video) == true) grant.copy(poster = null) else grant
    }

    /** 올린 사람(= [VideoAccessPolicy.canManage])의 삭제 */
    fun deleteOwn(caller: String, videoId: String) {
        store.find(videoId)?.takeIf { it.status != VideoStatus.DELETED && policy.canManage(caller, it) } ?: throw VideoNotFoundException()
        delete(videoId, "OWNER")
    }

    /**
     * 소유와 상관없는 삭제 — 운영자 경로와 앱 코드가 부른다. [reason] 은 로그에 남는 짧은 식별자(`REPORTED` 같은 것). 지웠으면 true, 이미 없었으면 false
     */
    fun delete(videoId: String, reason: String): Boolean {
        if (!REASON.matches(reason)) throw VideoInvalidException("reason")
        val closed = tx.run {
            store.markDeleted(videoId, time.now()).also { if (it) { followUps.finalize(videoId, VideoFinalizeEvent.DELETED); followUps.finalize(videoId, VideoFinalizeEvent.DELETED, lateCleanupDelay) } }
        }
        if (closed) log.info("video deleted video={} reason={}", videoId, reason)
        return closed
    }

    private companion object {
        val REASON = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$")
    }
}
