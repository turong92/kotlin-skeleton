package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedUploadPart
import dev.sumin.skeleton.storage.ListUploadedPartsRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedMultipartUploadPart
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.UploadedPart
import java.security.SecureRandom
import java.util.Base64
import org.slf4j.LoggerFactory

/** 올리기를 연 결과 — 브라우저가 [partSize] 씩 잘라 [partCount] 개를 올린다 */
data class VideoStarted(val videoId: String, val uploadId: String, val partSize: Long, val partCount: Int)

/**
 * 저장소로 직접 올리는 멀티파트 올리기. 서버는 파일을 지나가지 않는다 — 시작 · 조각 주소 · 조각 목록 · 마침(크기 확인) · 지우기만 한다.
 * 순서: 시작(형식 · 정책 · 파일 상한 · 동시 수 · 용량 — 여기까지는 빠른 거절) → 멀티파트 열기 → 줄 넣기(떨어지면 연 멀티파트를 닫는다) →
 * parts · 조각 목록(새로 고침 뒤 이어 올리기) → complete(멀티파트 마침 → HEAD 크기 → QUEUED, 멱등).
 * 남의 영상은 모든 호출이 [VideoNotFoundException] 이다. 변환 일꾼이 집는 것은 QUEUED 부터다. 로그에는 presign 주소를 남기지 않는다.
 */
class VideoUploads(
    private val props: VideoProperties,
    private val store: VideoStore,
    private val storage: PresignedStorage,
    private val policy: VideoAccessPolicy,
    private val quota: VideoQuota,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun start(caller: String, bytes: Long?, contentType: String?, purpose: String?, visibility: String?): VideoStarted {
        if (bytes == null || bytes <= 0) throw VideoInvalidException("bytes")
        if (purpose == null || !PURPOSE.matches(purpose)) throw VideoInvalidException("purpose")
        val vis = visibility?.let { v -> VideoVisibility.entries.firstOrNull { it.name == v } } ?: throw VideoInvalidException("visibility")
        if (contentType == null || !VideoLimits.allowedContentType(contentType)) throw VideoUnsupportedException()
        if (!policy.canUpload(caller, purpose, vis)) throw VideoForbiddenException()
        if (bytes > props.maxBytes.toBytes()) throw VideoTooLargeException(props.maxBytes.toBytes())
        if (store.countInFlight(caller) >= props.maxInFlight) throw VideoTooManyUploadsException(props.maxInFlight)
        if (!quota.allow(caller, bytes, purpose)) throw VideoQuotaException()

        val partSize = props.partSize.toBytes()
        val partCount = VideoLimits.partCount(bytes, partSize)
        val id = newId()
        val key = ObjectKey("${props.root}/u/$id")
        val multipart = storage.startMultipartUpload(StartMultipartUploadRequest(key, contentType.trim().lowercase()))
        try {
            store.insert(NewVideo(id, caller, purpose, vis, key.value, multipart.uploadId, bytes, time.now()))
        } catch (e: RuntimeException) {
            abortQuietly(key, multipart.uploadId)
            throw e
        }
        return VideoStarted(id, multipart.uploadId, partSize, partCount)
    }

    /** 요청한 번호마다 서명된 `PUT` 주소 — 브라우저가 저장소로 직접 올리고 응답의 `ETag` 를 모은다 */
    fun partUrls(caller: String, videoId: String, numbers: List<Int>?): List<PresignedMultipartUploadPart> {
        val video = uploading(caller, videoId)
        val partCount = VideoLimits.partCount(video.uploadBytes, props.partSize.toBytes())
        if (numbers.isNullOrEmpty() || numbers.size > MAX_PARTS_PER_CALL || numbers.distinct().size != numbers.size || numbers.any { it !in 1..partCount }) {
            throw VideoPartRangeException(partCount)
        }
        store.touchUpload(video.id, time.now())
        val key = ObjectKey(video.uploadKey!!)
        return numbers.map { storage.presignMultipartUploadPart(PresignedMultipartUploadPartRequest(key, video.uploadId!!, it)) }
    }

    /** 저장소가 가진 조각 — 새로 고침 뒤 같은 파일을 다시 고르면 빠진 번호만 올린다 */
    fun uploadedParts(caller: String, videoId: String): List<UploadedPart> {
        val video = uploading(caller, videoId)
        store.touchUpload(video.id, time.now())
        return storage.listParts(ListUploadedPartsRequest(ObjectKey(video.uploadKey!!), video.uploadId!!))
    }

    /**
     * 멀티파트를 마치고 올린 크기를 HEAD 로 확인해 QUEUED 로. **멱등** — 이미 UPLOADED 이후면 저장소를 다시 부르지 않고 지금 모양을 준다.
     * 크기가 처음 말한 값과 다르면 올린 파일을 지우고 DELETED(되돌릴 수 없다 — 새로 시작한다)
     */
    fun complete(caller: String, videoId: String, parts: List<Pair<Int, String>>?): Video {
        val video = find(caller, videoId)
        when (video.status) {
            VideoStatus.UPLOADING -> Unit
            VideoStatus.FAILED -> throw VideoBadStateException(video.status)
            else -> return video
        }
        val partCount = VideoLimits.partCount(video.uploadBytes, props.partSize.toBytes())
        if (parts == null || parts.map { it.first }.sorted() != (1..partCount).toList() || parts.any { it.second.isBlank() }) {
            throw VideoPartsInvalidException(partCount)
        }
        val key = ObjectKey(video.uploadKey!!)
        try {
            storage.completeMultipartUpload(
                CompleteMultipartUploadRequest(key, video.uploadId!!, parts.sortedBy { it.first }.map { CompletedUploadPart(it.first, it.second) }),
            )
        } catch (e: RuntimeException) {
            // 동시에 온 두 번째 complete 는 저장소가 이미 닫혔다고 답한다 — 앞선 쪽이 이겼으면 그 결과를 준다
            val now = store.find(video.id)!!
            if (now.status != VideoStatus.UPLOADING) return now
            // 앞선 호출이 마침은 성공했는데 크기 확인(HEAD)에서 죽었다면 저장소는 이미 닫혀 있고 객체가 있다 — 크기가 맞으면 이어서 간다(멱등)
            val already = runCatching { storage.metadata(key) }.getOrNull()
            if (already == null) {
                log.warn("video multipart completion failed video={} - {}", videoId, e.javaClass.simpleName)
                throw VideoUploadIncompleteException(e)
            }
        }
        if (storage.metadata(key)?.sizeBytes != video.uploadBytes) {
            runCatching { storage.delete(key) }
            store.markDeleted(video.id, time.now())
            throw VideoSizeMismatchException()
        }
        step(video.id, VideoStatus.UPLOADING, VideoEvent.COMPLETE)
        step(video.id, VideoStatus.UPLOADED, VideoEvent.VERIFIED)
        return store.find(video.id)!!
    }

    fun get(caller: String, videoId: String): Video = find(caller, videoId)

    fun list(caller: String, purpose: String?): List<Video> = store.listByOwner(caller, purpose)

    /** 지우기 — 줄을 DELETED 로 먼저(진실의 원천), 그다음 객체: 올리는 중이면 멀티파트 중단, 올린 파일 삭제 */
    fun delete(caller: String, videoId: String) {
        val video = find(caller, videoId)
        if (!store.markDeleted(video.id, time.now())) return
        val key = video.uploadKey?.let(::ObjectKey) ?: return
        if (video.status == VideoStatus.UPLOADING) video.uploadId?.let { abortQuietly(key, it) }
        runCatching { storage.delete(key) }.onFailure { log.warn("video upload file delete failed video={}", video.id) }
    }

    /**
     * 한 시간 넘게 움직임 없는 UPLOADING · UPLOADED 올리기를 치운다 — 멀티파트 중단 + 올린(고아) 객체 삭제, 줄은 FAILED `UPLOAD_ABANDONED`
     * (화면이 「다시 올려 주세요」로 보인다 — 지우면 아무 말 없이 사라진다). 청소 작업이 부른다. 치운 수
     */
    fun cleanupStaleUploads(limit: Int = CLEANUP_BATCH): Int {
        var cleaned = 0
        for (video in store.staleUploads(time.now().minus(props.staleUploadAge), limit)) {
            if (!store.markUploadAbandoned(video.id, time.now())) continue
            video.uploadKey?.let { key ->
                val objectKey = ObjectKey(key)
                if (video.status == VideoStatus.UPLOADING) video.uploadId?.let { abortQuietly(objectKey, it) }
                runCatching { storage.delete(objectKey) }.onFailure { log.warn("abandoned video upload delete failed video={}", video.id) }
            }
            cleaned++
        }
        return cleaned
    }

    private fun find(caller: String, videoId: String): Video {
        val video = store.find(videoId)?.takeIf { it.status != VideoStatus.DELETED && policy.canManage(caller, it) } ?: throw VideoNotFoundException()
        return video
    }

    private fun uploading(caller: String, videoId: String): Video =
        find(caller, videoId).also { if (it.status != VideoStatus.UPLOADING) throw VideoBadStateException(it.status) }

    /** 전이표([VideoStatus.next])가 정한 다음 상태로 — 진 쪽(동시에 온 다른 호출이 이미 했다)은 조용히 넘어간다 */
    private fun step(id: String, from: VideoStatus, event: VideoEvent) {
        store.advance(id, from, checkNotNull(from.next(event)) { "$from has no $event" }, time.now())
    }

    private fun abortQuietly(key: ObjectKey, uploadId: String) {
        runCatching { storage.abortMultipartUpload(AbortMultipartUploadRequest(key, uploadId)) }
            .onFailure { log.warn("video multipart abort failed - {}", it.javaClass.simpleName) }
    }

    private companion object {
        const val MAX_PARTS_PER_CALL = 100
        const val CLEANUP_BATCH = 100
        val PURPOSE = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
        val random = SecureRandom()

        /** 주소에 들어가는 무작위 id — 16바이트 base64url 22자 */
        fun newId(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }
}
