package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedUploadPart
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.PresignedUploadRequest
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.StorageFileCandidate
import dev.sumin.skeleton.storage.StorageFileValidator
import dev.sumin.skeleton.storage.StorageWebProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class StorageCandidateRequest(
    @field:NotBlank val fileName: String,
    val contentType: String? = null,
    @field:Min(0) val sizeBytes: Long,
)

data class StorageValidationResponse(val valid: Boolean, val errors: List<StorageValidationIssue>)

data class StorageValidationIssue(val code: String, val message: String)

data class StoragePresignedResponse(
    val key: String,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

data class StorageKeyRequest(@field:NotBlank val key: String)

data class StorageMultipartStartResponse(val key: String, val uploadId: String)

data class StorageMultipartPartRequest(
    @field:NotBlank val key: String,
    @field:NotBlank val uploadId: String,
    val partNumber: Int,
    @field:Min(0) val contentLength: Long? = null,
)

data class StorageMultipartPartResponse(
    val key: String,
    val uploadId: String,
    val partNumber: Int,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val expiresAt: Instant,
)

data class StoragePartRequest(val partNumber: Int, @field:NotBlank val eTag: String)

data class StorageMultipartCompleteRequest(
    @field:NotBlank val key: String,
    @field:NotBlank val uploadId: String,
    @field:NotEmpty @field:Valid val parts: List<StoragePartRequest>,
)

data class StorageMultipartCompleteResponse(val key: String, val uploadId: String, val eTag: String?)

data class StorageMultipartAbortRequest(@field:NotBlank val key: String, @field:NotBlank val uploadId: String)

data class StorageMultipartAbortResponse(val aborted: Boolean)

enum class StorageErrorCode(
    override val code: String,
    override val status: HttpStatus,
    override val title: String,
) : ErrorCode {
    FILE_REJECTED("STORAGE.FILE_REJECTED", HttpStatus.BAD_REQUEST, "File rejected"),
    OBJECT_NOT_FOUND("STORAGE.OBJECT_NOT_FOUND", HttpStatus.NOT_FOUND, "Object not found"),
    UNAUTHENTICATED("STORAGE.UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "Authentication required"),
}

/**
 * 브라우저가 저장소로 직접 올리고 내려받는 길 — presign · 멀티파트 · 검증을 HTTP 로 연다.
 *
 * - 호출자는 `Authentication.name`(auth 모듈에서는 계정 id). 인증 없는 호출은 401.
 * - 키는 서버가 정한다: `<keyPrefix>/<호출자>/<uuid>/<파일 이름>`. 클라이언트가 보낸 키는 **자기 접두사 아래일 때만** 받는다
 *   (남의 키 · 접두사 밖은 없는 것과 같은 404).
 * - 업로드는 [StorageFileValidator] 를 통과해야 presign 한다(`STORAGE.FILE_REJECTED`, `data.errors` 에 사유).
 * - [dev.sumin.skeleton.storage.PresignedStorage] 빈이 있고(버킷 설정) `skeleton.storage.web.enabled` 가 꺼져 있지 않을 때
 *   [StorageWebAutoConfiguration] 이 등록한다.
 */
@RestController
@RequestMapping("/api/v1/storage")
@Tag(name = "Storage")
class StorageController(
    private val storage: PresignedStorage,
    private val validator: StorageFileValidator,
    private val web: StorageWebProperties,
) {
    @Operation(summary = "Check a file against the server's upload rules", description = "Never fails for a rejected file; answers valid=false with the reasons.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageValidationResponse::class)
    @PostMapping("/validate")
    fun validate(authentication: Authentication?, @Valid @RequestBody request: StorageCandidateRequest): Any {
        authentication.accountId()
        val result = validator.validate(request.toCandidate())
        return Response.ok(StorageValidationResponse(result.valid, result.errors.map { StorageValidationIssue(it.code.name, it.message) }))
    }

    @Operation(summary = "Presign a direct upload", description = "The server picks the object key under the caller's prefix.")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StoragePresignedResponse::class)
    @PostMapping("/presign")
    fun presign(authentication: Authentication?, @Valid @RequestBody request: StorageCandidateRequest): Any {
        val accountId = authentication.accountId()
        requireAccepted(request)
        val presigned = storage.presignUpload(
            PresignedUploadRequest(newKey(accountId, request.fileName), request.contentType, request.sizeBytes),
        )
        return Response.ok(StoragePresignedResponse(presigned.key.value, presigned.method, presigned.url.toString(), presigned.headers, presigned.expiresAt))
    }

    @Operation(summary = "Presign a download of one of the caller's objects")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StoragePresignedResponse::class)
    @PostMapping("/presign-download")
    fun presignDownload(authentication: Authentication?, @Valid @RequestBody request: StorageKeyRequest): Any {
        val key = ownedKey(authentication.accountId(), request.key)
        val presigned = storage.presignDownload(PresignedDownloadRequest(key))
        return Response.ok(StoragePresignedResponse(presigned.key.value, presigned.method, presigned.url.toString(), presigned.headers, presigned.expiresAt))
    }

    @Operation(summary = "Start a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartStartResponse::class)
    @PostMapping("/multipart/start")
    fun startMultipart(authentication: Authentication?, @Valid @RequestBody request: StorageCandidateRequest): Any {
        val accountId = authentication.accountId()
        requireAccepted(request)
        val started = storage.startMultipartUpload(StartMultipartUploadRequest(newKey(accountId, request.fileName), request.contentType))
        return Response.ok(StorageMultipartStartResponse(started.key.value, started.uploadId))
    }

    @Operation(summary = "Presign one part of a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartPartResponse::class)
    @PostMapping("/multipart/part")
    fun presignPart(authentication: Authentication?, @Valid @RequestBody request: StorageMultipartPartRequest): Any {
        val key = ownedKey(authentication.accountId(), request.key)
        val part = storage.presignMultipartUploadPart(
            PresignedMultipartUploadPartRequest(key, request.uploadId, request.partNumber, request.contentLength),
        )
        return Response.ok(StorageMultipartPartResponse(part.key.value, part.uploadId, part.partNumber, part.method, part.url.toString(), part.headers, part.expiresAt))
    }

    @Operation(summary = "Complete a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartCompleteResponse::class)
    @PostMapping("/multipart/complete")
    fun completeMultipart(authentication: Authentication?, @Valid @RequestBody request: StorageMultipartCompleteRequest): Any {
        val key = ownedKey(authentication.accountId(), request.key)
        val completed = storage.completeMultipartUpload(
            CompleteMultipartUploadRequest(key, request.uploadId, request.parts.map { CompletedUploadPart(it.partNumber, it.eTag) }),
        )
        return Response.ok(StorageMultipartCompleteResponse(completed.key.value, completed.uploadId, completed.eTag))
    }

    @Operation(summary = "Abort a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartAbortResponse::class)
    @PostMapping("/multipart/abort")
    fun abortMultipart(authentication: Authentication?, @Valid @RequestBody request: StorageMultipartAbortRequest): Any {
        val key = ownedKey(authentication.accountId(), request.key)
        storage.abortMultipartUpload(AbortMultipartUploadRequest(key, request.uploadId))
        return Response.ok(StorageMultipartAbortResponse(aborted = true))
    }

    private fun requireAccepted(request: StorageCandidateRequest) {
        val result = validator.validate(request.toCandidate())
        if (!result.valid) {
            throw ApplicationException(
                message = "File rejected: " + result.errors.joinToString("; ") { it.code.name },
                errorCode = StorageErrorCode.FILE_REJECTED,
                data = mapOf("errors" to result.errors.map { StorageValidationIssue(it.code.name, it.message) }),
            )
        }
    }

    private fun newKey(accountId: String, fileName: String): ObjectKey =
        ObjectKey("${prefix(accountId)}${UUID.randomUUID()}/${safeFileName(fileName)}")

    private fun prefix(accountId: String): String = "${web.keyPrefix.trim('/')}/$accountId/"

    private fun ownedKey(accountId: String, raw: String): ObjectKey {
        val key = runCatching { ObjectKey(raw) }.getOrNull()
        if (key == null || !key.value.startsWith(prefix(accountId))) {
            throw ApplicationException("Object not found: $raw", StorageErrorCode.OBJECT_NOT_FOUND)
        }
        return key
    }

    private fun StorageCandidateRequest.toCandidate() = StorageFileCandidate(fileName, contentType, sizeBytes)

    private fun safeFileName(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
            .take(100)
            .ifBlank { "file" }

    private fun Authentication?.accountId(): String =
        this?.takeIf { it.isAuthenticated }?.name
            ?: throw ApplicationException("Authentication required", StorageErrorCode.UNAUTHENTICATED)
}
