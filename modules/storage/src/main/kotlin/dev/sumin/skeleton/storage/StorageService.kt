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
}

interface PresignedStorage : ObjectStorage {
    fun presignUpload(request: PresignedUploadRequest): PresignedUrl

    fun presignDownload(request: PresignedDownloadRequest): PresignedUrl

    fun startMultipartUpload(request: StartMultipartUploadRequest): StartedMultipartUpload

    fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest): PresignedMultipartUploadPart

    fun completeMultipartUpload(request: CompleteMultipartUploadRequest): CompletedMultipartUpload

    fun abortMultipartUpload(request: AbortMultipartUploadRequest)
}
