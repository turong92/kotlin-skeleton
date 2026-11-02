package dev.sumin.skeleton.storage

import java.net.URI
import java.time.Instant

data class StoredObjectMetadata(
    val key: ObjectKey,
    val sizeBytes: Long? = null,
    val contentType: String? = null,
    val eTag: String? = null,
    val lastModified: Instant? = null,
    val metadata: Map<String, String> = emptyMap(),
    val versionId: String? = null,
    val publicUrl: URI? = null,
)

fun interface StoragePublicUrlResolver {
    fun publicUrl(key: ObjectKey): URI?

    companion object {
        val NONE = StoragePublicUrlResolver { null }
    }
}

interface StorageService {
    fun metadata(key: ObjectKey): StoredObjectMetadata?

    fun delete(key: ObjectKey)

    /** 여러 키를 한 번에 삭제 (표시본 + 원본 등). 기본은 하나씩, S3 구현은 DeleteObjects 로 묶는다 */
    fun deleteAll(keys: Collection<ObjectKey>) {
        keys.forEach(::delete)
    }

    fun publicUrl(key: ObjectKey): URI? = null
}

interface ObjectStorage : StorageService {
    fun upload(request: UploadObjectRequest): StoredObjectWriteResult

    fun copy(request: CopyObjectRequest): StoredObjectWriteResult

    fun move(request: MoveObjectRequest): StoredObjectWriteResult {
        val copied = copy(
            CopyObjectRequest(
                sourceKey = request.sourceKey,
                destinationKey = request.destinationKey,
                metadata = request.metadata,
                contentType = request.contentType,
            ),
        )
        delete(request.sourceKey)
        return copied
    }

    fun list(request: ListObjectsRequest): ListedObjects

    /**
     * 객체의 바이트를 읽는다 - [range] 가 있으면 그 구간만(양 끝 포함, 객체 끝을 넘으면 끝까지). 서버가 올라온 내용을 보는 유일한 길(업로드 검사 · 이미지 변환).
     * 호출한 쪽이 스트림을 닫는다. 없는 객체는 예외. 읽기를 구현하지 않은 저장소는 [UnsupportedOperationException] 이다(옛 구현이 그대로 컴파일되도록 기본 구현이 던진다).
     */
    fun read(key: ObjectKey, range: LongRange? = null): java.io.InputStream =
        throw UnsupportedOperationException("${javaClass.name} does not support reading objects (ObjectStorage.read)")
}

interface PresignedStorage : ObjectStorage {
    fun presignUpload(request: PresignedUploadRequest): PresignedUrl

    fun presignDownload(request: PresignedDownloadRequest): PresignedUrl

    fun startMultipartUpload(request: StartMultipartUploadRequest): StartedMultipartUpload

    fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest): PresignedMultipartUploadPart

    fun completeMultipartUpload(request: CompleteMultipartUploadRequest): CompletedMultipartUpload

    fun abortMultipartUpload(request: AbortMultipartUploadRequest)
}
