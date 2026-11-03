package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.PresignedUploadRequest
import java.time.Instant

enum class DisplayState { QUEUED, RUNNING, DONE, FAILED }

/** 움직이는 이미지 하나의 표시본 변환 일 - [keyHash] 는 이미지 모듈의 상태 줄 열쇠(sha256 hex 64)이고 일꾼 API 의 job id 가 된다 */
data class DisplayJob(
    val keyHash: String,
    val objectKey: String,
    val state: DisplayState,
    val attempts: Int,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
)

/** 변환 일 표 - `video-jdbc` 가 구현한다. 전이는 모두 조건부 갱신이라 먼저 끝낸 쪽이 이긴다 */
interface DisplayJobStore {
    /** 이미 있으면 아무것도 안 한다 */
    fun enqueue(keyHash: String, objectKey: String, at: Instant)

    fun find(keyHash: String): DisplayJob?

    /** 가장 오래된 `QUEUED` 하나를 임대한다(시도 +1). 없으면 null */
    fun claimNext(owner: String, leaseUntil: Instant, at: Instant): DisplayJob?

    fun extendLease(keyHash: String, owner: String, until: Instant, at: Instant): Boolean

    /** `RUNNING` 이고 [owner] 의 일을 `DONE` 으로. 아니면 false */
    fun finish(keyHash: String, owner: String, at: Instant): Boolean

    /** `RUNNING` 인 일을 놓는다 - [retry] 이고 시도가 [VideoStatus.MAX_ATTEMPTS] 미만이면 다시 줄 세우고 아니면 `FAILED`. [owner] 가 null 이면 소유자를 안 본다. 아니면 false */
    fun release(keyHash: String, owner: String?, retry: Boolean, code: String, at: Instant): Boolean

    /** 임대가 끝난 `RUNNING` 일을 거둔다(위 [release] 와 같은 규칙) - 거둔 수 */
    fun expireLeases(now: Instant): Int
}

/** 이미지 모듈을 모르는 쪽에서 본 표시본 바꿔 끼우기 면 - `storage-image` 의 `AnimatedDisplays` 가 뒤에 있다 */
interface DisplayTarget {
    /** 변환본을 올릴 저장소 폴더(슬래시로 끝남) */
    fun folder(keyHash: String): String

    /** 지금 쓰이는 사본 display 의 확장자 - 움직이는 `READY` 이미지가 아니면 null */
    fun copyExtension(keyHash: String): String?

    /** `<폴더>display.<ext>` 를 표시본으로 쓴다. 바꿨거나 이미 바뀌어 있으면 true */
    fun use(keyHash: String, ext: String): Boolean
}

/**
 * 움직이는 GIF · WebP 의 표시본 변환(ANIMATED 차선). 서버는 사본 display 를 이미 쓰고 있다 - 이 일이 늦거나 실패해도 사본이 그대로 쓰이고, 끝났을 때만 표시본이 바뀐다.
 * 일꾼은 저장소 키를 갖지 않는다: 입력은 서명한 읽기 주소, 결과는 `display.mp4` · `display.webp` 둘 중 하나로만 서명한 올리기 주소 -
 * 사본 파일 이름(원본 형식 확장자)은 서명하지 않아 사본을 덮어쓰지 못한다
 */
class VideoDisplayJobs(
    private val props: VideoProperties,
    private val store: DisplayJobStore,
    private val target: DisplayTarget,
    private val storage: PresignedStorage,
    private val time: TimeProvider,
) {
    /** 이미지 모듈이 표시본 변환을 요청할 때 - 같은 이미지는 한 번만 줄을 선다 */
    fun enqueue(keyHash: String, objectKey: String) {
        store.enqueue(keyHash, objectKey, time.now())
    }

    /** 다음 일 하나. 이미지가 사라졌거나 더는 움직이는 이미지가 아닌 일은 내려받기 전에 닫는다 */
    fun claim(workerId: String): VideoJob? {
        val now = time.now()
        store.expireLeases(now)
        while (true) {
            val job = store.claimNext(workerId, now.plus(props.worker.leaseDuration), now) ?: return null
            val size = storage.metadata(ObjectKey(job.objectKey))?.sizeBytes
            if (size == null || size <= 0 || target.copyExtension(job.keyHash) == null) {
                store.release(job.keyHash, workerId, retry = false, code = OBSOLETE, at = now)
                continue
            }
            return VideoJob(
                jobId = job.keyHash, lane = VideoLane.ANIMATED, attempt = job.attempts, leaseUntil = job.leaseUntil!!,
                inputUrl = storage.presignDownload(PresignedDownloadRequest(ObjectKey(job.objectKey), expiresIn = props.worker.urlTtl)).url.toString(),
                inputBytes = size, width = null, height = null, durationMs = null, codec = null,
                expectedOutputBytes = size * 2, build = null, origFolder = null, verifyDecode = false,
            )
        }
    }

    fun heartbeat(workerId: String, keyHash: String): Instant {
        val until = time.now().plus(props.worker.leaseDuration)
        if (!store.extendLease(keyHash, workerId, until, time.now())) throw VideoLeaseLostException()
        return until
    }

    fun uploadUrls(workerId: String, keyHash: String, names: List<String>): List<UploadUrl> {
        leased(workerId, keyHash)
        if (names.isEmpty() || names.size > MAX_NAMES) throw VideoWorkerInvalidException("names")
        if (names.any { !allowed(keyHash, it) }) throw VideoPathNotAllowedException()
        return names.map { name ->
            val signed = storage.presignUpload(
                PresignedUploadRequest(key = ObjectKey("${target.folder(keyHash)}$name"), contentType = VideoResultVerifier.contentTypeOf(name), expiresIn = props.worker.urlTtl, cacheControl = CACHE_CONTROL),
            )
            UploadUrl(name, signed.method, signed.url.toString(), signed.headers, signed.expiresAt)
        }
    }

    /** 올린 파일이 보고한 크기대로 있으면 표시본을 바꾼다. 이미 끝난 일에 같은 호출이 또 와도 변화 없이 돌아온다 */
    fun complete(workerId: String, keyHash: String, file: ResultFile) {
        val job = store.find(keyHash) ?: throw VideoNotFoundException()
        if (job.state == DisplayState.DONE) return
        if (job.state != DisplayState.RUNNING || job.leaseOwner != workerId) throw VideoLeaseLostException()
        if (!allowed(keyHash, file.name)) throw VideoPathNotAllowedException()
        val key = ObjectKey("${target.folder(keyHash)}${file.name}")
        val actual = storage.metadata(key)?.sizeBytes ?: throw VideoResultMismatchException("missing")
        if (actual != file.bytes || actual <= 0) throw VideoResultMismatchException("size")
        if (!target.use(keyHash, file.name.substringAfterLast('.'))) {
            // 그 사이 이미지가 지워졌다 - 올린 변환본이 고아가 되지 않게 치우고 일을 닫는다
            storage.delete(key)
            store.release(keyHash, workerId, retry = false, code = OBSOLETE, at = time.now())
            return
        }
        if (!store.finish(keyHash, workerId, time.now())) throw VideoLeaseLostException()
    }

    /** 일꾼이 낸 실패 - 표시본은 건드리지 않는다(사본이 계속 쓰인다) */
    fun fail(workerId: String, keyHash: String, code: String, retry: Boolean) {
        if (code !in VideoFailCode.WORKER_REPORTABLE) throw VideoFailCodeInvalidException()
        val job = store.find(keyHash) ?: throw VideoNotFoundException()
        if (job.state == DisplayState.FAILED || job.state == DisplayState.DONE) return   // 이미 닫혔다 - 같은 보고가 또 왔다
        if (!store.release(keyHash, workerId, retry, code, time.now())) throw VideoLeaseLostException()
    }

    private fun leased(workerId: String, keyHash: String): DisplayJob =
        (store.find(keyHash) ?: throw VideoNotFoundException()).also {
            if (it.state != DisplayState.RUNNING || it.leaseOwner != workerId) throw VideoLeaseLostException()
        }

    /** 허용 이름이고 사본 파일 이름(`display.<원본 형식>`)이 아니다 */
    private fun allowed(keyHash: String, name: String): Boolean =
        VideoResultVerifier.allowedName(VideoLane.ANIMATED, null, name) && name.substringAfterLast('.') != target.copyExtension(keyHash)

    companion object {
        const val MAX_NAMES = 4
        const val OBSOLETE = "OBSOLETE"
        const val CACHE_CONTROL = "private, max-age=31536000, immutable"

        private val ID = Regex("^[0-9a-f]{64}$")

        /** 이 일꾼 API 의 job id 가 영상 id(base64url 22자)가 아니라 변환 일(sha256 hex 64자)인가 */
        fun isJobId(jobId: String): Boolean = ID.matches(jobId)
    }
}
