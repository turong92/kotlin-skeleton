package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedStorage
import java.time.Duration
import org.slf4j.LoggerFactory

/** 서버 후속이 따라붙는 사건 — `video.finalize` 작업의 내용 */
enum class VideoFinalizeEvent {
    /** remux 가 끝나 원본 단이 올라갔다 */
    PLAYABLE,
    COMPAT_READY,
    LADDER_READY,

    /** remux 가 끝내 실패했다 */
    FAILED,

    /** 호환 · 사다리가 끝내 실패했다 — 영상은 계속 재생된다 */
    TRACK_FAILED,

    /** 원본 단 검증 [VideoProperties.cleanupDelay] 뒤 — 올린 원 파일을 지운다 */
    UPLOAD_CLEANUP,

    /** 트랙이 끝난(또는 실패한) 뒤 — 이긴 쪽 결과만 남기고 늦은 쪽 · 옛 시도의 결과를 쓸어낸다 */
    SWEEP,
}

/**
 * 후속 작업을 큐에 넣는 포트 — `video-jdbc` 가 `job-queue-jdbc` 로 채운다. **상태를 바꾸는 같은 트랜잭션 안에서** 부른다
 * (저장되면 반드시 실행되고, 롤백되면 같이 사라진다). 처리는 [VideoFinalizer] · [VideoWakeRunner]
 */
interface VideoFollowUps {
    /** `video.finalize` — [delay] 뒤에 처리 */
    fun finalize(videoId: String, event: VideoFinalizeEvent, delay: Duration = Duration.ZERO)

    /** `video.wake` — 호환 · 사다리를 집을 일꾼을 깨운다([VideoWaker.enabled] 일 때만 부른다) */
    fun wake()
}

/** 한 트랜잭션 — `video-jdbc` 가 채운다. 안에서 부른 저장소 쓰기와 [VideoFollowUps] 가 함께 커밋되거나 함께 사라진다. 이미 트랜잭션 안이면 그 안에 합류한다 */
interface VideoTransactions {
    fun <T> run(block: () -> T): T
}

/**
 * 영상의 사건을 앱에 알린다 — 머리 포인터 · 캐시 · 알림을 앱이 붙이는 자리. `video.finalize` 가 부르니 **적어도 한 번** 온다(멱등하게 쓴다).
 * 던지면 후속 작업이 재시도된다. 기본 [NONE] 은 아무것도 안 한다
 */
interface VideoEvents {
    /** 원본 단이 올라가 재생할 수 있게 됐다 */
    fun onPlayable(video: Video) {}

    /** 호환 단 · 사다리가 준비됐다 */
    fun onTrackReady(video: Video, lane: VideoLane) {}

    /** 변환이 끝내 실패했다([Video.failCode]) */
    fun onFailed(video: Video) {}

    /** 영상이 지워졌다 */
    fun onDeleted(video: Video) {}

    companion object {
        val NONE: VideoEvents = object : VideoEvents {}
    }
}

/** 깨운 실행의 이름(점검용 · 비밀 아님) — 모르면 null */
data class WakeTicket(val execution: String?)

/** 깨우기 실패 — 메시지에는 상태 코드 · 예외 종류만(토큰 · 키 · 응답 본문은 안 싣는다) */
class VideoWakeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 호환 · 사다리 일꾼을 **깨우는** 이음매 — 0 으로 줄어드는 실행 환경(Cloud Run 등)만 필요하다. 상주하는 일꾼은 스스로 집으니 기본 [NoopWaker].
 * 깨우기가 실패해도 자동으로 다른 곳으로 넘기지 않는다 — 일은 큐에 남는다
 */
interface VideoWaker {
    /** 꺼진 구현이면 false — 서비스가 깨우기 작업을 만들지도 부르지도 않는다 */
    val enabled: Boolean

    /** 한 번 깨운다. 실패는 [VideoWakeException] */
    fun wake(): WakeTicket
}

object NoopWaker : VideoWaker {
    override val enabled: Boolean = false
    override fun wake(): WakeTicket = WakeTicket(null)
}

/** `video.wake` 처리 — 집을 호환 · 사다리 일이 있을 때만 깨운다(헛 깨우기는 돈이다). 상한 · 최소 간격은 깨우기 구현의 몫 */
class VideoWakeRunner(private val waker: VideoWaker, private val store: VideoStore) {
    fun run() {
        if (!waker.enabled || store.countClaimableTracks() == 0) return
        waker.wake()
    }
}

/**
 * `video.finalize` 가 하는 일 — 앱에 사건 전달, 올린 원 파일 삭제, 이긴 쪽만 남기는 쓸기. **재시도돼도 같은 결과**(멱등). 지운 영상은 아무것도 안 한다.
 * 지우는 쪽이라 조건을 좁게 잡는다: 원 파일은 영상이 `PLAYABLE` 이고 원본 단 키가 있을 때만(= `complete` 가 폴더를 검증한 뒤에만)
 */
class VideoFinalizer(
    private val store: VideoStore,
    private val storage: PresignedStorage,
    private val keys: VideoKeys,
    private val events: VideoEvents,
    private val time: TimeProvider = TimeProvider.systemUtc(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(videoId: String, event: VideoFinalizeEvent) {
        val video = store.find(videoId) ?: return
        if (video.status == VideoStatus.DELETED) return
        when (event) {
            VideoFinalizeEvent.PLAYABLE -> if (video.status == VideoStatus.PLAYABLE) events.onPlayable(video)
            VideoFinalizeEvent.COMPAT_READY -> if (video.status == VideoStatus.PLAYABLE) events.onTrackReady(video, VideoLane.COMPAT)
            VideoFinalizeEvent.LADDER_READY -> if (video.status == VideoStatus.PLAYABLE) events.onTrackReady(video, VideoLane.LADDER)
            VideoFinalizeEvent.FAILED -> if (video.status == VideoStatus.FAILED) events.onFailed(video)
            VideoFinalizeEvent.TRACK_FAILED -> log.warn("video track failed for good video={} code={}", video.id, video.failCode)
            VideoFinalizeEvent.UPLOAD_CLEANUP -> cleanupUpload(video)
            VideoFinalizeEvent.SWEEP -> sweep(video)
        }
    }

    private fun cleanupUpload(video: Video) {
        if (video.status != VideoStatus.PLAYABLE || video.origKey == null) return
        val key = video.uploadKey ?: return
        storage.delete(ObjectKey(key))
        store.clearUploadKey(video.id, time.now())
    }

    /** 이긴 쪽 결과(원본 단 · 포스터 · 호환 단 · 지금 사다리 폴더)만 남긴다. 일꾼이 임대 중이면 진행 중인 결과일 수 있어 건드리지 않는다 — 그 일이 끝나면 다음 쓸기가 따라붙는다 */
    private fun sweep(video: Video) {
        if (video.leaseOwner != null) return
        val folder = keys.folder(video)
        val playable = video.status == VideoStatus.PLAYABLE
        val keep = if (playable) setOfNotNull(video.origKey, video.posterKey, video.compatKey) else emptySet()
        val keepFolders = if (playable) listOfNotNull(playlistFolder(video.origKey), playlistFolder(video.compatKey)) else emptyList()
        val ladderFolder = video.takeIf { playable && it.ladderStatus == TrackStatus.READY }?.ladderBuild?.let { "${folder}h-$it/" }
        val doomed = mutableListOf<ObjectKey>()
        var token: String? = null
        do {
            val listed = storage.list(ListObjectsRequest(prefix = folder, continuationToken = token, maxKeys = 1000))
            listed.objects.filter { o ->
                o.key.value !in keep && keepFolders.none { o.key.value.startsWith(it) } && (ladderFolder == null || !o.key.value.startsWith(ladderFolder))
            }.forEach { doomed += it.key }
            token = listed.nextContinuationToken.takeIf { listed.truncated }
        } while (token != null)
        doomed.forEach { storage.delete(it) }
        if (doomed.isNotEmpty()) log.info("video sweep video={} removed={}", video.id, doomed.size)
    }

    /** 변형 목록 키(`…/o-<해시>/index.m3u8`)의 폴더 접두사 */
    private fun playlistFolder(key: String?): String? = key?.takeIf { it.endsWith("/index.m3u8") }?.substringBeforeLast('/')?.plus("/")
}
