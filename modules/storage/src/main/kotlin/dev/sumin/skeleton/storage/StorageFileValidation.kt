package dev.sumin.skeleton.storage

data class StorageFileCandidate(
    val fileName: String,
    val contentType: String? = null,
    val sizeBytes: Long,
) {
    init {
        require(fileName.isNotBlank()) { "File name must not be blank." }
        require(sizeBytes >= 0) { "File size must not be negative." }
    }
}

data class StorageFileValidationRule(
    val maxSizeBytes: Long = Long.MAX_VALUE,
    val allowedContentTypes: Set<String> = emptySet(),
    val allowedExtensions: Set<String> = emptySet(),
) {
    init {
        require(maxSizeBytes >= 0) { "Maximum size must not be negative." }
    }
}

data class StorageFileValidationResult(
    val errors: List<StorageFileValidationError>,
) {
    val valid: Boolean = errors.isEmpty()
}

data class StorageFileValidationError(
    val code: StorageFileValidationErrorCode,
    val message: String,
)

enum class StorageFileValidationErrorCode {
    SIZE_TOO_LARGE,
    UNSUPPORTED_CONTENT_TYPE,
    UNSUPPORTED_EXTENSION,
}

class StorageFileValidationException(
    val result: StorageFileValidationResult,
) : IllegalArgumentException(
    result.errors.joinToString("; ") { "${it.code}: ${it.message}" },
)

class StorageFileValidator(
    private val rule: StorageFileValidationRule,
) {
    fun validate(candidate: StorageFileCandidate): StorageFileValidationResult {
        val errors = mutableListOf<StorageFileValidationError>()

        if (candidate.sizeBytes > rule.maxSizeBytes) {
            errors += StorageFileValidationError(
                StorageFileValidationErrorCode.SIZE_TOO_LARGE,
                "File size ${candidate.sizeBytes} exceeds maximum ${rule.maxSizeBytes}.",
            )
        }

        if (rule.allowedContentTypes.isNotEmpty() && !contentTypeAllowed(candidate.contentType)) {
            errors += StorageFileValidationError(
                StorageFileValidationErrorCode.UNSUPPORTED_CONTENT_TYPE,
                "Content type '${candidate.contentType.orEmpty()}' is not allowed.",
            )
        }

        if (rule.allowedExtensions.isNotEmpty() && !extensionAllowed(candidate.fileName)) {
            errors += StorageFileValidationError(
                StorageFileValidationErrorCode.UNSUPPORTED_EXTENSION,
                "File extension for '${candidate.fileName}' is not allowed.",
            )
        }

        return StorageFileValidationResult(errors)
    }

    fun requireValid(candidate: StorageFileCandidate) {
        val result = validate(candidate)
        if (!result.valid) {
            throw StorageFileValidationException(result)
        }
    }

    private fun contentTypeAllowed(contentType: String?): Boolean {
        val normalized = contentType?.lowercase()?.trim().orEmpty()
        if (normalized.isBlank()) {
            return false
        }

        return rule.allowedContentTypes.any { allowed ->
            val candidate = allowed.lowercase().trim()
            candidate == normalized ||
                (candidate.endsWith("/*") && normalized.startsWith(candidate.removeSuffix("*")))
        }
    }

    private fun extensionAllowed(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .trim()
        if (extension.isBlank() || extension == fileName.lowercase()) {
            return false
        }

        return rule.allowedExtensions
            .map { it.removePrefix(".").lowercase().trim() }
            .any { it == extension }
    }
}
