package dev.sumin.skeleton.storage.s3

import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedMultipartUploadPart
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedUrl
import dev.sumin.skeleton.storage.StoredObjectMetadata
import java.net.URI
import java.time.Duration
import software.amazon.awssdk.awscore.presigner.PresignedRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload as AwsCompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart as AwsCompletedPart
import software.amazon.awssdk.services.s3.model.HeadObjectResponse

internal fun PresignedRequest.toPresignedUrl(key: ObjectKey): PresignedUrl =
    PresignedUrl(
        key = key,
        method = httpRequest().method().name,
        url = url().toURI(),
        headers = signedHeadersAsStrings(),
        expiresAt = expiration(),
    )

internal fun PresignedRequest.toMultipartUploadPart(
    request: PresignedMultipartUploadPartRequest,
): PresignedMultipartUploadPart =
    PresignedMultipartUploadPart(
        key = request.key,
        uploadId = request.uploadId,
        partNumber = request.partNumber,
        method = httpRequest().method().name,
        url = url().toURI(),
        headers = signedHeadersAsStrings(),
        expiresAt = expiration(),
    )

internal fun CompleteMultipartUploadRequest.toAwsCompletedMultipartUpload(): AwsCompletedMultipartUpload =
    AwsCompletedMultipartUpload.builder()
        .parts(
            parts
                .sortedBy { it.partNumber }
                .map { part ->
                    AwsCompletedPart.builder()
                        .partNumber(part.partNumber)
                        .eTag(part.eTag)
                        .build()
                },
        )
        .build()

internal fun HeadObjectResponse.toStoredObjectMetadata(
    key: ObjectKey,
    publicUrl: URI?,
): StoredObjectMetadata =
    StoredObjectMetadata(
        key = key,
        sizeBytes = contentLength(),
        contentType = contentType(),
        eTag = eTag(),
        lastModified = lastModified(),
        metadata = metadata(),
        versionId = versionId(),
        publicUrl = publicUrl,
    )

internal fun requirePositiveDuration(duration: Duration, name: String) {
    require(!duration.isZero && !duration.isNegative) { "$name must be positive." }
}

private fun PresignedRequest.signedHeadersAsStrings(): Map<String, String> =
    signedHeaders().mapValues { (_, values) -> values.joinToString(",") }
