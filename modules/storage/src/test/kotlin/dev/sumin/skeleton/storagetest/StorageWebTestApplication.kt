package dev.sumin.skeleton.storagetest

import dev.sumin.skeleton.storage.*
import java.net.URI
import java.time.Instant
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class StorageWebTestApplication {
    @Bean
    fun presignedStorage(): PresignedStorage = FakePresignedStorage()
}

/** 서명 없이 요청을 그대로 URL 에 담는 가짜 — 컨트롤러가 무엇을 시켰는지만 본다 */
class FakePresignedStorage : PresignedStorage {
    val uploads = mutableListOf<PresignedUploadRequest>()
    val aborted = mutableListOf<AbortMultipartUploadRequest>()
    private val expires = Instant.parse("2030-01-01T00:00:00Z")

    override fun presignUpload(request: PresignedUploadRequest): PresignedUrl {
        uploads += request
        return PresignedUrl(request.key, "PUT", URI("http://s3.test/${request.key}?up"), mapOf("content-type" to (request.contentType ?: "")), expires)
    }

    override fun presignDownload(request: PresignedDownloadRequest) =
        PresignedUrl(request.key, "GET", URI("http://s3.test/${request.key}?down"), emptyMap(), expires)

    override fun startMultipartUpload(request: StartMultipartUploadRequest) = StartedMultipartUpload(request.key, "upload-1")

    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) =
        PresignedMultipartUploadPart(request.key, request.uploadId, request.partNumber, "PUT", URI("http://s3.test/${request.key}?part=${request.partNumber}"), emptyMap(), expires)

    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) = CompletedMultipartUpload(request.key, request.uploadId, "etag-all")

    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) {
        aborted += request
    }

    /** 올라와 있는 객체 - 완료 호출 시험이 채운다 (키 -> 바이트 · 선언한 Content-Type) */
    val objects = linkedMapOf<String, Pair<ByteArray, String?>>()
    val reads = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    override fun upload(request: UploadObjectRequest): StoredObjectWriteResult {
        objects[request.key.value] = request.content to request.contentType
        return StoredObjectWriteResult(request.key)
    }

    override fun copy(request: CopyObjectRequest): StoredObjectWriteResult {
        val source = objects[request.sourceKey.value]
        if (source != null) objects[request.destinationKey.value] = source.first to (request.contentType ?: source.second)
        return StoredObjectWriteResult(request.destinationKey)
    }

    override fun list(request: ListObjectsRequest) = ListedObjects(objects.keys.filter { it.startsWith(request.prefix) }.map { StoredObjectSummary(ObjectKey(it)) })
    override fun metadata(key: ObjectKey): StoredObjectMetadata? = objects[key.value]?.let { StoredObjectMetadata(key, sizeBytes = it.first.size.toLong(), contentType = it.second) }

    override fun read(key: ObjectKey, range: LongRange?): java.io.InputStream {
        reads += key.value
        val bytes = objects[key.value]?.first ?: error("no such object: ${key.value}")
        return (if (range == null) bytes else bytes.copyOfRange(range.first.toInt(), minOf(bytes.size.toLong(), range.last + 1).toInt())).inputStream()
    }

    override fun delete(key: ObjectKey) {
        deleted += key.value
        objects.remove(key.value)
    }
}
