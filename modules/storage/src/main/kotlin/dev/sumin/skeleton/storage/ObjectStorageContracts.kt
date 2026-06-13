package dev.sumin.skeleton.storage

import java.net.URI
import java.time.Instant

data class UploadObjectRequest(
    val key: ObjectKey,
    val content: ByteArray,
    val contentType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(content.isNotEmpty()) { "Upload content must not be empty." }
        contentType?.let { require(it.isNotBlank()) { "Content type must not be blank." } }
        requireMetadata(metadata)
    }
}

data class CopyObjectRequest(
    val sourceKey: ObjectKey,
    val destinationKey: ObjectKey,
    val metadata: Map<String, String> = emptyMap(),
    val contentType: String? = null,
) {
    init {
        require(sourceKey != destinationKey) { "Copy source and destination keys must differ." }
        contentType?.let { require(it.isNotBlank()) { "Content type must not be blank." } }
        requireMetadata(metadata)
    }
}

data class MoveObjectRequest(
    val sourceKey: ObjectKey,
    val destinationKey: ObjectKey,
    val metadata: Map<String, String> = emptyMap(),
    val contentType: String? = null,
) {
    init {
        require(sourceKey != destinationKey) { "Move source and destination keys must differ." }
        contentType?.let { require(it.isNotBlank()) { "Content type must not be blank." } }
        requireMetadata(metadata)
    }
}

data class StoredObjectWriteResult(
    val key: ObjectKey,
    val eTag: String? = null,
    val versionId: String? = null,
    val publicUrl: URI? = null,
)

data class ListObjectsRequest(
    val prefix: String = "",
    val continuationToken: String? = null,
    val maxKeys: Int? = null,
) {
    init {
        require(!prefix.startsWith("/")) { "List prefix must be relative, not absolute." }
        require(prefix.none { it.isISOControl() || it == '\\' }) {
            "List prefix must not contain control characters or backslashes."
        }
        require(prefix.split('/').none { it == "." || it == ".." }) {
            "List prefix must not contain path traversal segments."
        }
        continuationToken?.let { require(it.isNotBlank()) { "Continuation token must not be blank." } }
        maxKeys?.let { require(it in 1..1000) { "Max keys must be between 1 and 1000." } }
    }
}

data class StoredObjectSummary(
    val key: ObjectKey,
    val sizeBytes: Long? = null,
    val eTag: String? = null,
    val lastModified: Instant? = null,
    val publicUrl: URI? = null,
)

data class ListedObjects(
    val objects: List<StoredObjectSummary>,
    val nextContinuationToken: String? = null,
    val truncated: Boolean = false,
)

private fun requireMetadata(metadata: Map<String, String>) {
    require(metadata.keys.none { it.isBlank() }) { "Metadata keys must not be blank." }
}
