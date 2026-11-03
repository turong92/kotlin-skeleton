package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.CopyObjectRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StorageKeyOwnership
import java.security.MessageDigest

/** 영상 폴더 안 포스터 파일의 이름 — 일꾼이 올린 것(`p-<16>.jpg`)과 이미지 검사를 거쳐 들어온 것(`poster-<16>.<확장자>`) */
internal object VideoPosterNames {
    private val WORKER = Regex("^p-[0-9a-f]{16}\\.jpg$")
    private val CHECKED = Regex("^poster-[0-9a-f]{16}\\.(jpg|png|webp)$")

    fun isWorker(key: String) = WORKER.matches(key.substringAfterLast('/'))

    fun isPoster(key: String) = isWorker(key) || CHECKED.matches(key.substringAfterLast('/'))
}

/**
 * 영상 포스터를 파일 묶음(이미지 모듈, [UploadImages] 포트)의 이미지 길로 보낸다 — 업로드 검사 · 메타데이터 제거 · 표시본 굽기를 거친 그림만 포스터가 된다.
 * 이미지 모듈이 없는 조립에서는 아무것도 안 한다: 일꾼이 올린 포스터를 그대로 쓰고, 바꿔 끼우기는 [VideoPosterRejectedException] 이다.
 *
 * - [adopt] — 일꾼이 올린 포스터(표지 그림 또는 영상 한 장면)를 검사에 부치고, 끝나면 표시본 사본으로 바꾼다. 그때까지 재생 허가는 포스터를 비운다([hides]).
 * - [replace] — 올린 사람이 파일 묶음으로 올린 자기 이미지를 포스터로 지정한다. 검사가 끝난 이미지만(지금 못 끝났으면 [VideoPosterPendingException]).
 *
 * 포스터 객체는 늘 영상 폴더 안에 있다(공개 영상이면 공개 영역) — 표시본을 거기로 복사하므로 영상 폴더 삭제가 포스터까지 치운다. 옛 포스터는 쓸기(`SWEEP`)가 지운다
 */
class VideoPosters(
    private val store: VideoStore,
    private val storage: PresignedStorage,
    private val keys: VideoKeys,
    private val policy: VideoAccessPolicy,
    private val ownership: StorageKeyOwnership,
    private val images: () -> UploadImages?,
    private val followUps: VideoFollowUps,
    private val tx: VideoTransactions,
    private val time: TimeProvider,
) {
    val enabled: Boolean get() = images() != null

    /** 일꾼이 올린 포스터가 아직 검사를 거치지 않았다 — 재생 허가가 내주지 않는다 */
    fun hides(video: Video): Boolean = enabled && video.posterKey?.let(VideoPosterNames::isWorker) == true

    /** 올린 사람의 바꿔 끼우기. 남의 영상 · 남의 이미지는 같은 404 */
    fun replace(caller: String, videoId: String, imageKey: String) {
        val video = store.find(videoId)?.takeIf { it.status != VideoStatus.DELETED && policy.canManage(caller, it) } ?: throw VideoNotFoundException()
        if (video.status != VideoStatus.PLAYABLE) throw VideoNotReadyException(video.status)
        if (!ownership.owns(caller, imageKey)) throw VideoNotFoundException()
        val module = images() ?: throw VideoPosterRejectedException()
        val status = module.status(imageKey) ?: run { module.ensure(imageKey); null }
        when (status?.state) {
            UploadImageState.READY -> if (!place(video, status)) throw VideoPosterRejectedException()
            null, UploadImageState.PENDING -> throw VideoPosterPendingException()
            else -> throw VideoPosterRejectedException()
        }
    }

    /** `video.finalize POSTER` — 일꾼이 올린 포스터를 검사에 부치고 끝나면 바꾼다. 검사가 안 끝났으면 던져 큐가 늦춰 다시 한다. 멱등 */
    fun adopt(videoId: String) {
        val video = store.find(videoId)?.takeIf { it.status == VideoStatus.PLAYABLE } ?: return
        val raw = video.posterKey?.takeIf(VideoPosterNames::isWorker) ?: return
        val module = images() ?: return
        val status = module.status(raw) ?: run { module.ensure(raw); null }
        when (status?.state) {
            UploadImageState.READY -> if (!place(video, status)) drop(video)
            null, UploadImageState.PENDING -> throw VideoPosterPendingException()
            else -> drop(video)
        }
        module.forget(listOf(raw))
    }

    /** 검사를 거친 그림을 영상 폴더로 복사해 포스터로 삼는다. 정지 그림을 낼 수 없으면 false */
    private fun place(video: Video, status: UploadImageStatus): Boolean {
        // 움직이는 이미지의 표시본은 GIF · 영상이다 - 포스터는 정지 그림이어야 하므로 첫 장 썸네일을 쓴다
        val variant = status.variants[UploadImages.DISPLAY]?.takeUnless { it.contentType == "video/mp4" || it.contentType == "image/gif" } ?: status.variants[UploadImages.THUMB] ?: return false
        val ext = variant.key.substringAfterLast('.', "")
        if (ext !in STILL_EXTENSIONS) return false
        val key = "${keys.folder(video)}poster-${sha256(variant.key).take(16)}.$ext"
        storage.copy(CopyObjectRequest(ObjectKey(variant.key), ObjectKey(key), contentType = variant.contentType, cacheControl = keys.cacheControl(video)))
        change(video, key, variant.width, variant.height)
        return true
    }

    private fun drop(video: Video) = change(video, null, null, null)

    private fun change(video: Video, key: String?, width: Int?, height: Int?) {
        val changed = tx.run {
            store.replacePoster(video.id, key, width, height, time.now()).also { if (it) followUps.finalize(video.id, VideoFinalizeEvent.SWEEP) }
        }
        if (!changed) throw VideoNotFoundException()
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val STILL_EXTENSIONS = setOf("jpg", "png", "webp")
    }
}
