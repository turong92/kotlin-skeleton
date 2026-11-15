package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.storage.AbortMultipartUploadRequest
import dev.sumin.skeleton.storage.CompleteMultipartUploadRequest
import dev.sumin.skeleton.storage.CompletedUploadPart
import dev.sumin.skeleton.storage.ListUploadedPartsRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedMultipartUploadPartRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.PresignedUploadRequest
import dev.sumin.skeleton.storage.StartMultipartUploadRequest
import dev.sumin.skeleton.storage.StorageFileCandidate
import dev.sumin.skeleton.storage.StorageFileValidationErrorCode
import dev.sumin.skeleton.storage.StorageFileValidator
import dev.sumin.skeleton.storage.StorageKeyScheme
import dev.sumin.skeleton.storage.StorageUploadCompletion
import dev.sumin.skeleton.storage.UploadCompletionResult
import dev.sumin.skeleton.storage.StorageWebProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.storage.StorageCandidateInput
import dev.sumin.skeleton.storage.StorageGuestUploads
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
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

/** 비회원 업로드 발급 - 파일 하나. [captchaToken] 은 `guest.captcha=REQUIRED` 일 때만 쓴다 */
data class StorageGuestCandidateRequest(
    @field:NotBlank val fileName: String,
    val contentType: String? = null,
    @field:Min(0) val sizeBytes: Long,
    val captchaToken: String? = null,
)

data class StorageKeyRequest(@field:NotBlank val key: String)

/** 완료 호출의 응답 - [image] 는 이미지 모듈이 있고 이 파일을 이미지로 등록했을 때만 */
data class StorageCompleteResponse(val key: String, val contentType: String?, val sizeBytes: Long, val image: StorageImageInfo?)

data class StorageImageInfo(val status: String, val width: Int?, val height: Int?)

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
    RATE_LIMITED("STORAGE.RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many uploads"),
    CAPTCHA_FAILED("STORAGE.CAPTCHA_FAILED", HttpStatus.BAD_REQUEST, "Captcha failed"),
    MULTIPART_DISABLED("STORAGE.MULTIPART_DISABLED", HttpStatus.NOT_FOUND, "Multipart upload disabled"),
}

/**
 * 브라우저가 저장소로 직접 올리고 내려받는 길 — presign · 멀티파트 · 검증을 HTTP 로 연다.
 *
 * - 호출자는 `Authentication.name`(auth 모듈에서는 계정 id). 인증 없는 호출은 401.
 * - 키는 서버가 정한다: `<keyPrefix>/<호출자>/<uuid>/<파일 이름>` (`key-owner=OPAQUE` 면 `<keyPrefix>/o/<uuid>.<mac>/file[.<ext>]` — [StorageKeyScheme]). 클라이언트가 보낸 키는 **자기 접두사 아래일 때만** 받는다
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
    private val keys: StorageKeyScheme = StorageKeyScheme(web),
    private val guestUploads: StorageGuestUploads? = null,
    private val clientIps: ClientIps = ClientIps(),
    private val completion: StorageUploadCompletion = StorageUploadCompletion(storage),
) {
    @Operation(
        summary = "Tell the server a direct upload is done, so it can inspect the bytes",
        description = "Reads the head of the caller's object, checks its content against the declared type (and any UploadInspector), and answers the sniffed type. " +
            "A refused file is deleted (400 STORAGE.FILE_REJECTED). Someone else's key is a 404 and nothing is read. Idempotent.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageCompleteResponse::class)
    @PostMapping("/complete")
    fun complete(authentication: Authentication?, @Valid @RequestBody request: StorageKeyRequest): Any {
        val key = ownedKey(authentication.accountId(), request.key)
        return Response.ok(completion.complete(key, validator.rule.maxSizeBytes).toResponse())
    }

    @Operation(
        summary = "Complete a guest upload (the same inspection, without signing in)",
        description = "Only when skeleton.storage.web.guest.enabled (else 401). Counted per IP separately from presigning; a key that is not a genuine guest key is a 404.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageCompleteResponse::class)
    @PostMapping("/guest/complete")
    fun completeGuest(http: HttpServletRequest, @Valid @RequestBody request: StorageKeyRequest): Any {
        val uploads = guestUploads ?: throw ApplicationException("Authentication required", StorageErrorCode.UNAUTHENTICATED)
        uploads.admitCompletion(clientIps.of(http).limitKey)
        val key = runCatching { ObjectKey(request.key) }.getOrNull()
        if (key == null || !keys.isGuestKey(key.value)) throw ApplicationException("Object not found: ${request.key}", StorageErrorCode.OBJECT_NOT_FOUND)
        return Response.ok(completion.complete(key, keys.properties().guest.maxSizeBytes).toResponse())
    }

    @Operation(
        summary = "Presign a direct image upload without signing in",
        description = "Only when skeleton.storage.web.guest.enabled (else 401). One image, an IP rate limit, no SVG; the key is `<prefix>/g/<uuid>.<mac>/file.<ext>`.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StoragePresignedResponse::class)
    @PostMapping("/guest/presign")
    fun presignGuest(authentication: Authentication?, http: HttpServletRequest, @Valid @RequestBody request: StorageGuestCandidateRequest): Any {
        // 로그인한 사람은 회원 경로를 쓴다 - 이 경로는 비회원 전용이라 로그인 여부와 무관하게 같은 규칙으로 돈다
        val uploads = guestUploads ?: throw ApplicationException("Authentication required", StorageErrorCode.UNAUTHENTICATED)
        val address = clientIps.of(http)
        val key = uploads.issue(StorageCandidateInput(request.fileName, request.contentType, request.sizeBytes), request.captchaToken, address.ip, address.limitKey)
        val presigned = storage.presignUpload(PresignedUploadRequest(key, request.contentType!!.trim().lowercase(), request.sizeBytes))
        return Response.ok(StoragePresignedResponse(presigned.key.value, presigned.method, presigned.url.toString(), presigned.headers, presigned.expiresAt))
    }

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
        requireMultipart()
        val accountId = authentication.accountId()
        requireAccepted(request)
        val started = storage.startMultipartUpload(StartMultipartUploadRequest(newKey(accountId, request.fileName), request.contentType))
        return Response.ok(StorageMultipartStartResponse(started.key.value, started.uploadId))
    }

    @Operation(summary = "Presign one part of a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartPartResponse::class)
    @PostMapping("/multipart/part")
    fun presignPart(authentication: Authentication?, @Valid @RequestBody request: StorageMultipartPartRequest): Any {
        requireMultipart()
        val key = ownedKey(authentication.accountId(), request.key)
        // 서명 전에 거절한다 (최종 크기는 완료 때 다시 본다). 길이가 서명에 안 들어가면 조각이 얼마든 커질 수 있어 contentLength 는 필수다
        val max = validator.rule.maxSizeBytes
        val length = request.contentLength ?: throw partRejected("CONTENT_LENGTH_REQUIRED", "contentLength is required for a multipart part.")
        if (length > max) throw sizeRejected(length, max)
        // 마지막 조각 말고는 모두 5MiB 이상이라야 하므로 상한을 채우는 데 필요한 조각 수가 번호의 한도다
        val maxParts = (max / MIN_PART_BYTES + if (max % MIN_PART_BYTES == 0L) 0 else 1).coerceAtLeast(1)
        if (request.partNumber > maxParts) throw partRejected("PART_NUMBER_TOO_HIGH", "Part number ${request.partNumber} exceeds $maxParts, the most parts the size limit $max allows.")
        val part = storage.presignMultipartUploadPart(
            PresignedMultipartUploadPartRequest(key, request.uploadId, request.partNumber, request.contentLength),
        )
        return Response.ok(StorageMultipartPartResponse(part.key.value, part.uploadId, part.partNumber, part.method, part.url.toString(), part.headers, part.expiresAt))
    }

    @Operation(summary = "Complete a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartCompleteResponse::class)
    @PostMapping("/multipart/complete")
    fun completeMultipart(authentication: Authentication?, @Valid @RequestBody request: StorageMultipartCompleteRequest): Any {
        requireMultipart()
        val key = ownedKey(authentication.accountId(), request.key)
        val max = validator.rule.maxSizeBytes
        // 마치기 전에 올라간 조각의 합을 본다 - 넘으면 마치지 않고 abort 해 객체가 아예 안 생긴다. 조각 목록을 모르는 저장소는 아래 마친 뒤 검사만 한다
        val uploaded = try {
            storage.listParts(ListUploadedPartsRequest(key, request.uploadId)).filter { part -> request.parts.any { it.partNumber == part.partNumber } }.sumOf { it.sizeBytes }
        } catch (e: UnsupportedOperationException) {
            null
        }
        if (uploaded != null && uploaded > max) {
            storage.abortMultipartUpload(AbortMultipartUploadRequest(key, request.uploadId))
            throw sizeRejected(uploaded, max)
        }
        val completed = storage.completeMultipartUpload(
            CompleteMultipartUploadRequest(key, request.uploadId, request.parts.map { CompletedUploadPart(it.partNumber, it.eTag) }),
        )
        // 조각 크기는 클라이언트가 정하므로 합친 최종 크기가 상한을 넘으면 지우고 거절한다
        val size = storage.metadata(key)?.sizeBytes
        if (size != null && size > max) {
            storage.delete(key)
            throw sizeRejected(size, max)
        }
        return Response.ok(StorageMultipartCompleteResponse(completed.key.value, completed.uploadId, completed.eTag))
    }

    @Operation(summary = "Abort a multipart upload")
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageMultipartAbortResponse::class)
    @PostMapping("/multipart/abort")
    fun abortMultipart(authentication: Authentication?, @Valid @RequestBody request: StorageMultipartAbortRequest): Any {
        requireMultipart()
        val key = ownedKey(authentication.accountId(), request.key)
        storage.abortMultipartUpload(AbortMultipartUploadRequest(key, request.uploadId))
        return Response.ok(StorageMultipartAbortResponse(aborted = true))
    }

    private fun requireMultipart() {
        if (!web.multipart.enabled) throw ApplicationException("Multipart upload is disabled", StorageErrorCode.MULTIPART_DISABLED)
    }

    private fun sizeRejected(size: Long, max: Long) = partRejected(StorageFileValidationErrorCode.SIZE_TOO_LARGE.name, "File size $size exceeds maximum $max.")

    private fun partRejected(code: String, message: String) = ApplicationException(
        message = "File rejected: $code",
        errorCode = StorageErrorCode.FILE_REJECTED,
        data = mapOf("errors" to listOf(StorageValidationIssue(code, message))),
    )

    private companion object {
        /** S3 계열 멀티파트의 최소 조각 크기(마지막 조각 제외) */
        const val MIN_PART_BYTES = 5L * 1024 * 1024
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
        ObjectKey(keys.issue(accountId, fileName))

    private fun ownedKey(accountId: String, raw: String): ObjectKey {
        val key = runCatching { ObjectKey(raw) }.getOrNull()
        if (key == null || !keys.owns(accountId, key.value)) {
            throw ApplicationException("Object not found: $raw", StorageErrorCode.OBJECT_NOT_FOUND)
        }
        return key
    }

    private fun UploadCompletionResult.toResponse() =
        StorageCompleteResponse(key, contentType, sizeBytes, image?.let { StorageImageInfo(it.state.name, it.width, it.height) })

    private fun StorageCandidateRequest.toCandidate() = StorageFileCandidate(fileName, contentType, sizeBytes)

    private fun Authentication?.accountId(): String =
        this?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", StorageErrorCode.UNAUTHENTICATED)
}
