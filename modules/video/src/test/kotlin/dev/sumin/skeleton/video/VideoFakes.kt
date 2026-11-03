package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedMultipartUpload
import dev.sumin.skeleton.storage.CopyObjectRequest
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ListUploadedPartsRequest
import dev.sumin.skeleton.storage.ListedObjects
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedMultipartUploadPart
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.PresignedUploadRequest
import dev.sumin.skeleton.storage.PresignedUrl
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.StartedMultipartUpload
import dev.sumin.skeleton.storage.StoredObjectMetadata
import dev.sumin.skeleton.storage.StoredObjectWriteResult
import dev.sumin.skeleton.storage.UploadObjectRequest
import dev.sumin.skeleton.storage.UploadedPart
import java.net.URI
import java.time.Instant

/** 저장소 호출을 기록하는 가짜 — 올린 객체는 [objects](키 → 바이트 수) 에 둔다 */
class FakeStorage : PresignedStorage {
    val started = mutableListOf<StartMultipartUploadRequest>()
    val aborted = mutableListOf<AbortMultipartUploadRequest>()
    val completed = mutableListOf<CompleteMultipartUploadRequest>()
    val deleted = mutableListOf<String>()
    val listed = mutableListOf<ListUploadedPartsRequest>()
    val objects = linkedMapOf<String, Long>()
    var parts: List<UploadedPart> = emptyList()
    var failComplete = false
    private val expires = Instant.parse("2030-01-01T00:00:00Z")

    override fun startMultipartUpload(request: StartMultipartUploadRequest): StartedMultipartUpload {
        started += request
        return StartedMultipartUpload(request.key, "up-${started.size}")
    }

    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) =
        PresignedMultipartUploadPart(request.key, request.uploadId, request.partNumber, "PUT", URI("http://s3.test/${request.key.value}?part=${request.partNumber}"), emptyMap(), expires)

    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest): CompletedMultipartUpload {
        if (failComplete) error("NoSuchUpload")
        completed += request
        return CompletedMultipartUpload(request.key, request.uploadId, "etag-all")
    }

    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) {
        aborted += request
    }

    override fun listParts(request: ListUploadedPartsRequest): List<UploadedPart> {
        listed += request
        return parts
    }

    override fun metadata(key: ObjectKey): StoredObjectMetadata? = objects[key.value]?.let { StoredObjectMetadata(key, sizeBytes = it) }

    override fun delete(key: ObjectKey) {
        deleted += key.value
        objects.remove(key.value)
    }

    override fun upload(request: UploadObjectRequest) = StoredObjectWriteResult(request.key)
    override fun copy(request: CopyObjectRequest) = StoredObjectWriteResult(request.destinationKey)
    override fun list(request: ListObjectsRequest) = ListedObjects(emptyList())
    override fun presignUpload(request: PresignedUploadRequest) = PresignedUrl(request.key, "PUT", URI("http://s3.test/${request.key.value}"), emptyMap(), expires)
    override fun presignDownload(request: PresignedDownloadRequest) = PresignedUrl(request.key, "GET", URI("http://s3.test/${request.key.value}"), emptyMap(), expires)
}

/** 저장소 포트의 메모리 구현 — 전이 규칙은 진짜 구현(video-jdbc)과 같은 조건부 갱신이다 */
class FakeVideoStore : VideoStore {
    val rows = linkedMapOf<String, Video>()
    var failInsert = false

    override fun insert(video: NewVideo): Video {
        if (failInsert) error("db down")
        return Video(
            id = video.id, ownerAccountId = video.ownerAccountId, purpose = video.purpose, visibility = video.visibility, status = VideoStatus.UPLOADING,
            uploadKey = video.uploadKey, uploadId = video.uploadId, uploadBytes = video.uploadBytes, lastUploadActivityAt = video.at, createdAt = video.at, updatedAt = video.at,
        ).also { rows[it.id] = it }
    }

    override fun find(id: String) = rows[id]

    override fun listByOwner(ownerAccountId: String, purpose: String?) =
        rows.values.filter { it.ownerAccountId == ownerAccountId && it.status != VideoStatus.DELETED && (purpose == null || it.purpose == purpose) }

    override fun countInFlight(ownerAccountId: String) =
        rows.values.count { it.ownerAccountId == ownerAccountId && it.status in IN_FLIGHT }

    override fun touchUpload(id: String, at: Instant) {
        rows.computeIfPresent(id) { _, v -> v.copy(lastUploadActivityAt = at) }
    }

    override fun advance(id: String, from: VideoStatus, to: VideoStatus, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == from } ?: return false
        rows[id] = v.copy(status = to, updatedAt = at, uploadId = if (to == VideoStatus.QUEUED) null else v.uploadId)
        return true
    }

    override fun markDeleted(id: String, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status != VideoStatus.DELETED } ?: return false
        rows[id] = v.copy(status = VideoStatus.DELETED, updatedAt = at)
        return true
    }

    override fun markUploadAbandoned(id: String, at: Instant): Boolean {
        val v = rows[id]?.takeIf { it.status == VideoStatus.UPLOADING || it.status == VideoStatus.UPLOADED } ?: return false
        rows[id] = v.copy(status = VideoStatus.FAILED, failCode = "UPLOAD_ABANDONED", updatedAt = at)
        return true
    }

    override fun staleUploads(before: Instant, limit: Int) =
        rows.values.filter { (it.status == VideoStatus.UPLOADING || it.status == VideoStatus.UPLOADED) && it.lastUploadActivityAt < before }.take(limit)

    private companion object {
        val IN_FLIGHT = setOf(VideoStatus.UPLOADING, VideoStatus.UPLOADED, VideoStatus.QUEUED, VideoStatus.REMUXING)
    }
}
