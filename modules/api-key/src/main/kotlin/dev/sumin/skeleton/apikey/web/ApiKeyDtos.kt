package dev.sumin.skeleton.apikey.web

import dev.sumin.skeleton.account.web.SocialReauthRequest
import dev.sumin.skeleton.apikey.ApiKey
import dev.sumin.skeleton.apikey.ApiKeyStatus
import dev.sumin.skeleton.apikey.CreatedApiKey
import dev.sumin.skeleton.apikey.DeclaredApiKeyScope
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/** 키 만들기 - 다시 인증 증거(`currentPassword` · `confirmationCode` · `socialReauth` 중 계정에 맞는 하나)가 함께 온다 */
data class CreateApiKeyRequest(
    @field:NotBlank @field:Size(max = 100) val name: String?,
    @field:NotEmpty @field:Size(max = 100) val scopes: List<@Size(max = 64) String>?,
    val expiresAt: Instant? = null,
    @field:Size(max = 128) val currentPassword: String? = null,
    @field:Pattern(regexp = "^[0-9]{6}$") val confirmationCode: String? = null,
    @field:Valid val socialReauth: SocialReauthRequest? = null,
) {
    override fun toString() = "CreateApiKeyRequest(name=$name, scopes=$scopes, expiresAt=$expiresAt, <credentials redacted>)"
}

enum class RevokedBy { USER, OPERATOR }

/** 토큰 · 해시 · 운영자 사유는 없다 */
data class ApiKeyResponse(
    val id: String,
    val name: String,
    val hint: String,
    val scopes: List<String>,
    val status: ApiKeyStatus,
    val revokedBy: RevokedBy?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
)

/** 토큰은 이 응답에만 있다 */
data class CreatedApiKeyResponse(val key: ApiKeyResponse, val token: String) {
    override fun toString() = "CreatedApiKeyResponse(key=$key, token=<redacted>)"
}

data class ApiKeyScopeResponse(val key: String, val module: String)

fun ApiKey.toResponse(now: Instant) = ApiKeyResponse(
    id = id, name = name, hint = tokenHint, scopes = scopes.sorted(), status = status(now),
    revokedBy = revokedBy?.let { if (it == ownerId) RevokedBy.USER else RevokedBy.OPERATOR },
    createdAt = createdAt, expiresAt = expiresAt, lastUsedAt = lastUsedAt,
)

fun CreatedApiKey.toResponse(now: Instant) = CreatedApiKeyResponse(key.toResponse(now), token)

fun DeclaredApiKeyScope.toResponse() = ApiKeyScopeResponse(key, module)

/** 운영자가 보는 한 줄 - 내 목록의 필드에 주인 · 회수 사유가 더 있다. [ownerName] 은 `account.read` 도 가진 운영자에게만 온다. 토큰 · 해시는 없다 */
data class AdminApiKeyResponse(
    val id: String,
    val ownerId: String,
    val ownerName: String?,
    val name: String,
    val hint: String,
    val scopes: List<String>,
    val status: ApiKeyStatus,
    val revokedBy: RevokedBy?,
    val revokedReason: String?,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
)

data class OperatorRevokeRequest(@field:NotBlank @field:Size(max = 200) val reason: String?)

fun ApiKey.toAdminResponse(now: Instant, ownerName: String?): AdminApiKeyResponse {
    val row = toResponse(now)
    return AdminApiKeyResponse(
        id = id, ownerId = ownerId, ownerName = ownerName, name = name, hint = tokenHint, scopes = row.scopes, status = row.status,
        revokedBy = row.revokedBy, revokedReason = revokedReason, createdAt = createdAt, expiresAt = expiresAt, lastUsedAt = lastUsedAt,
    )
}
