package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedMultipartUpload
import dev.sumin.skeleton.storage.CopyObjectRequest
import dev.sumin.skeleton.storage.ListObjectsRequest
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
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** S3 없이 테스트하는 메모리 저장소 — 업로드한 바이트를 `objects` 에서 확인한다. 서명한 URL 은 가짜 호스트를 가리킨다. */
open class FakePresignedStorage : PresignedStorage {
    val objects = ConcurrentHashMap<String, ByteArray>()

    /** 객체의 Content-Type - 올릴 때 · 복사할 때 따라간다 */
    val types = ConcurrentHashMap<String, String>()

    private fun url(key: ObjectKey, method: String) =
        PresignedUrl(key, method, URI.create("http://storage.test/${key.value}"), emptyMap(), Instant.now().plusSeconds(600))

    override fun upload(request: UploadObjectRequest): StoredObjectWriteResult {
        objects[request.key.value] = request.content
        request.contentType?.let { types[request.key.value] = it }
        return StoredObjectWriteResult(request.key)
    }

    override fun copy(request: CopyObjectRequest): StoredObjectWriteResult {
        objects[request.destinationKey.value] = requireNotNull(objects[request.sourceKey.value])
        (request.contentType ?: types[request.sourceKey.value])?.let { types[request.destinationKey.value] = it }
        return StoredObjectWriteResult(request.destinationKey)
    }

    override fun list(request: ListObjectsRequest): ListedObjects = ListedObjects(emptyList())

    override fun metadata(key: ObjectKey): StoredObjectMetadata? =
        objects[key.value]?.let { StoredObjectMetadata(key, sizeBytes = it.size.toLong(), contentType = types[key.value]) }

    override fun read(key: ObjectKey, range: LongRange?): java.io.InputStream {
        val bytes = requireNotNull(objects[key.value]) { "no such object: ${key.value}" }
        return (if (range == null) bytes else bytes.copyOfRange(range.first.toInt(), minOf(bytes.size.toLong(), range.last + 1).toInt())).inputStream()
    }

    override fun delete(key: ObjectKey) {
        objects.remove(key.value)
        types.remove(key.value)
    }

    override fun presignUpload(request: PresignedUploadRequest): PresignedUrl = url(request.key, "PUT")

    override fun presignDownload(request: PresignedDownloadRequest): PresignedUrl = url(request.key, "GET")

    override fun startMultipartUpload(request: StartMultipartUploadRequest) = StartedMultipartUpload(request.key, "upload-1")

    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) =
        PresignedMultipartUploadPart(request.key, request.uploadId, request.partNumber, "PUT", URI.create("http://storage.test/part"), emptyMap(), Instant.now().plusSeconds(600))

    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) = CompletedMultipartUpload(request.key, request.uploadId, "etag")

    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) = Unit
}
