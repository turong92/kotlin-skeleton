package dev.sumin.skeleton.storage

import java.net.URI
import java.time.Duration
import java.time.Instant

data class PresignedUploadRequest(
    val key: ObjectKey,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val expiresIn: Duration? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        contentType?.let { require(it.isNotBlank()) { "Content type must not be blank." } }
        contentLength?.let { require(it >= 0) { "Content length must not be negative." } }
        expiresIn?.let(::requirePositiveDuration)
        requireMetadata(metadata)
    }
}

data class PresignedDownloadRequest(
    val key: ObjectKey,
    val expiresIn: Duration? = null,
    val responseContentDisposition: String? = null,
) {
    init {
        expiresIn?.let(::requirePositiveDuration)
        responseContentDisposition?.let {
            require(it.isNotBlank()) { "Response content disposition must not be blank." }
        }
    }
}

data class PresignedUrl(
    val key: ObjectKey,
    val method: String,
    val url: URI,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Instant,
) {
    init {
        require(method.isNotBlank()) { "HTTP method must not be blank." }
    }
}

data class StartMultipartUploadRequest(
    val key: ObjectKey,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        contentType?.let { require(it.isNotBlank()) { "Content type must not be blank." } }
        requireMetadata(metadata)
    }
}

data class StartedMultipartUpload(
    val key: ObjectKey,
    val uploadId: String,
) {
    init {
        require(uploadId.isNotBlank()) { "Upload id must not be blank." }
    }
}

data class PresignedMultipartUploadPartRequest(
    val key: ObjectKey,
    val uploadId: String,
    val partNumber: Int,
    val contentLength: Long? = null,
    val expiresIn: Duration? = null,
) {
    init {
        require(uploadId.isNotBlank()) { "Upload id must not be blank." }
        requireValidPartNumber(partNumber)
        contentLength?.let { require(it >= 0) { "Content length must not be negative." } }
        expiresIn?.let(::requirePositiveDuration)
    }
}

data class PresignedMultipartUploadPart(
    val key: ObjectKey,
    val uploadId: String,
    val partNumber: Int,
    val method: String,
    val url: URI,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Instant,
) {
    init {
        require(uploadId.isNotBlank()) { "Upload id must not be blank." }
        requireValidPartNumber(partNumber)
        require(method.isNotBlank()) { "HTTP method must not be blank." }
    }
}

data class CompletedUploadPart(
    val partNumber: Int,
    val eTag: String,
) {
    init {
        requireValidPartNumber(partNumber)
        require(eTag.isNotBlank()) { "ETag must not be blank." }
    }
}

data class CompleteMultipartUploadRequest(
    val key: ObjectKey,
    val uploadId: String,
    val parts: List<CompletedUploadPart>,
) {
    init {
        require(uploadId.isNotBlank()) { "Upload id must not be blank." }
        require(parts.isNotEmpty()) { "Multipart upload completion requires at least one part." }
        require(parts.map { it.partNumber }.distinct().size == parts.size) {
            "Multipart upload completion parts must have unique part numbers."
        }
    }
}

data class CompletedMultipartUpload(
    val key: ObjectKey,
    val uploadId: String,
    val eTag: String? = null,
    val versionId: String? = null,
) {
    init {
        require(uploadId.isNotBlank()) { "Upload id must not be blank." }
    }
}

data class AbortMultipartUploadRequest(
    val key: ObjectKey,
    val uploadId: String,
) {
    init {
        require(uploadId.isNotBlank()) { "Upload id must not be blank." }
    }
}

private fun requirePositiveDuration(duration: Duration) {
    require(!duration.isZero && !duration.isNegative) { "Duration must be positive." }
}

private fun requireValidPartNumber(partNumber: Int) {
    require(partNumber in 1..10_000) { "Multipart part number must be between 1 and 10000." }
}

private fun requireMetadata(metadata: Map<String, String>) {
    require(metadata.keys.none { it.isBlank() }) { "Metadata keys must not be blank." }
}
