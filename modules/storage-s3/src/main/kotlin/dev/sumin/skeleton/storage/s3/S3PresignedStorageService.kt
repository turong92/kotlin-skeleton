package dev.sumin.skeleton.storage.s3

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
import dev.sumin.skeleton.storage.StoragePublicUrlResolver
import dev.sumin.skeleton.storage.StoredObjectSummary
import dev.sumin.skeleton.storage.StoredObjectWriteResult
import dev.sumin.skeleton.storage.StoredObjectMetadata
import dev.sumin.skeleton.storage.UploadObjectRequest
import java.net.URI
import java.time.Duration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest as AwsAbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest as AwsCompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CopyObjectRequest as AwsCopyObjectRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.MetadataDirective
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest

class S3PresignedStorageService(
    private val bucket: String,
    private val s3Client: S3Client,
    private val presigner: S3Presigner,
    private val uploadPresignDuration: Duration,
    private val downloadPresignDuration: Duration,
    private val multipartPartPresignDuration: Duration,
    private val publicUrlResolver: StoragePublicUrlResolver = StoragePublicUrlResolver.NONE,
) : PresignedStorage {
    init {
        require(bucket.isNotBlank()) { "S3 bucket must not be blank." }
        requirePositiveDuration(uploadPresignDuration, "Upload presign duration")
        requirePositiveDuration(downloadPresignDuration, "Download presign duration")
        requirePositiveDuration(multipartPartPresignDuration, "Multipart part presign duration")
    }

    override fun upload(request: UploadObjectRequest): StoredObjectWriteResult {
        val objectRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(request.key.value)
            .apply {
                request.contentType?.let { contentType(it) }
                request.cacheControl?.let { cacheControl(it) }
                request.contentDisposition?.let { contentDisposition(it) }
                if (request.metadata.isNotEmpty()) {
                    metadata(request.metadata)
                }
            }
            .build()
        val response = s3Client.putObject(objectRequest, RequestBody.fromBytes(request.content))

        return StoredObjectWriteResult(
            key = request.key,
            eTag = response.eTag(),
            versionId = response.versionId(),
            publicUrl = publicUrl(request.key),
        )
    }

    override fun copy(request: CopyObjectRequest): StoredObjectWriteResult {
        val objectRequest = AwsCopyObjectRequest.builder()
            .sourceBucket(bucket)
            .sourceKey(request.sourceKey.value)
            .destinationBucket(bucket)
            .destinationKey(request.destinationKey.value)
            .apply {
                if (request.metadata.isNotEmpty() || request.contentType != null) {
                    metadataDirective(MetadataDirective.REPLACE)
                    if (request.metadata.isNotEmpty()) {
                        metadata(request.metadata)
                    }
                    request.contentType?.let { contentType(it) }
                }
            }
            .build()
        val response = s3Client.copyObject(objectRequest)

        return StoredObjectWriteResult(
            key = request.destinationKey,
            eTag = response.copyObjectResult()?.eTag(),
            versionId = response.versionId(),
            publicUrl = publicUrl(request.destinationKey),
        )
    }

    override fun list(request: ListObjectsRequest): ListedObjects {
        val objectRequest = ListObjectsV2Request.builder()
            .bucket(bucket)
            .apply {
                if (request.prefix.isNotBlank()) {
                    prefix(request.prefix)
                }
                request.continuationToken?.let { continuationToken(it) }
                request.maxKeys?.let { maxKeys(it) }
            }
            .build()
        val response = s3Client.listObjectsV2(objectRequest)

        return ListedObjects(
            objects = response.contents().mapNotNull { item ->
                item.key()?.let { key ->
                    val objectKey = ObjectKey(key)
                    StoredObjectSummary(
                        key = objectKey,
                        sizeBytes = item.size(),
                        eTag = item.eTag(),
                        lastModified = item.lastModified(),
                        publicUrl = publicUrl(objectKey),
                    )
                }
            },
            nextContinuationToken = response.nextContinuationToken(),
            truncated = response.isTruncated == true,
        )
    }

    override fun presignUpload(request: PresignedUploadRequest): PresignedUrl {
        val objectRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(request.key.value)
            .apply {
                request.contentType?.let { contentType(it) }
                request.contentLength?.let { contentLength(it) }
                if (request.metadata.isNotEmpty()) {
                    metadata(request.metadata)
                }
            }
            .build()
        val duration = request.expiresIn ?: uploadPresignDuration
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(duration)
                .putObjectRequest(objectRequest)
                .build(),
        )

        return presigned.toPresignedUrl(request.key)
    }

    override fun presignDownload(request: PresignedDownloadRequest): PresignedUrl {
        val objectRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(request.key.value)
            .apply {
                request.responseContentDisposition?.let { responseContentDisposition(it) }
            }
            .build()
        val duration = request.expiresIn ?: downloadPresignDuration
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(objectRequest)
                .build(),
        )

        return presigned.toPresignedUrl(request.key)
    }

    override fun startMultipartUpload(request: StartMultipartUploadRequest): StartedMultipartUpload {
        val objectRequest = CreateMultipartUploadRequest.builder()
            .bucket(bucket)
            .key(request.key.value)
            .apply {
                request.contentType?.let { contentType(it) }
                if (request.metadata.isNotEmpty()) {
                    metadata(request.metadata)
                }
            }
            .build()
        val response = s3Client.createMultipartUpload(objectRequest)

        return StartedMultipartUpload(
            key = request.key,
            uploadId = response.uploadId(),
        )
    }

    override fun presignMultipartUploadPart(
        request: PresignedMultipartUploadPartRequest,
    ): PresignedMultipartUploadPart {
        val objectRequest = UploadPartRequest.builder()
            .bucket(bucket)
            .key(request.key.value)
            .uploadId(request.uploadId)
            .partNumber(request.partNumber)
            .apply {
                request.contentLength?.let { contentLength(it) }
            }
            .build()
        val duration = request.expiresIn ?: multipartPartPresignDuration
        val presigned = presigner.presignUploadPart(
            UploadPartPresignRequest.builder()
                .signatureDuration(duration)
                .uploadPartRequest(objectRequest)
            .build(),
        )

        return presigned.toMultipartUploadPart(request)
    }

    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest): CompletedMultipartUpload {
        val objectRequest = AwsCompleteMultipartUploadRequest.builder()
            .bucket(bucket)
            .key(request.key.value)
            .uploadId(request.uploadId)
            .multipartUpload(request.toAwsCompletedMultipartUpload())
            .build()
        val response = s3Client.completeMultipartUpload(objectRequest)

        return CompletedMultipartUpload(
            key = request.key,
            uploadId = request.uploadId,
            eTag = response.eTag(),
            versionId = response.versionId(),
        )
    }

    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) {
        s3Client.abortMultipartUpload(
            AwsAbortMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(request.key.value)
                .uploadId(request.uploadId)
                .build(),
        )
    }

    override fun metadata(key: ObjectKey): StoredObjectMetadata? =
        try {
            val response = s3Client.headObject(
                HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key.value)
                    .build(),
            )
            response.toStoredObjectMetadata(key, publicUrl(key))
        } catch (error: S3Exception) {
            if (error.statusCode() == 404) {
                null
            } else {
                throw error
            }
        }

    /** DeleteObjects 는 요청당 1000개 제한 → 묶어서 보낸다 */
    override fun deleteAll(keys: Collection<ObjectKey>) {
        keys.distinct().chunked(1000).forEach { chunk ->
            val objects = chunk.map { ObjectIdentifier.builder().key(it.value).build() }
            s3Client.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(objects).quiet(true).build())
                    .build(),
            )
        }
    }

    override fun delete(key: ObjectKey) {
        s3Client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key.value)
                .build(),
        )
    }

    override fun publicUrl(key: ObjectKey): URI? =
        publicUrlResolver.publicUrl(key)
}
