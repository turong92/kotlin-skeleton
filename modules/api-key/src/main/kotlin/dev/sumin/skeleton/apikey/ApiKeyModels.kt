package dev.sumin.skeleton.apikey

import java.time.Instant

object ApiKeyOwnerTypes {
    /** 계정 소유 키 - 주인 id 가 계정 id. 다음 판의 소유자 종류(워크스페이스 …)는 [ApiKeyOwnerPolicy] 를 구현해 더한다 */
    const val ACCOUNT = "ACCOUNT"
}

data class ApiKeyOwner(val type: String, val id: String)

enum class ApiKeyStatus { ACTIVE, EXPIRED, REVOKED }

/**
 * 키 한 줄. 토큰 원문은 어디에도 없다 - [tokenHash] 는 SHA-256(16진 64자), [tokenHint] 는 화면에 보일 `접두사…끝 4자`.
 * [revokedBy] 는 회수한 계정 id(본인이면 주인 자신, 운영자면 운영자) - 응답의 `USER`/`OPERATOR` 는 이 값과 주인을 비교해 만든다.
 */
data class ApiKey(
    val id: String,
    val ownerType: String,
    val ownerId: String,
    val createdBy: String,
    val name: String,
    val tokenHash: String,
    val tokenHint: String,
    val scopes: Set<String>,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
    val revokedBy: String?,
    val revokedReason: String?,
    val lastUsedAt: Instant?,
    val lastUsedIp: String?,
    val createdAt: Instant,
) {
    fun status(now: Instant): ApiKeyStatus = when {
        revokedAt != null -> ApiKeyStatus.REVOKED
        expiresAt != null && !expiresAt.isAfter(now) -> ApiKeyStatus.EXPIRED
        else -> ApiKeyStatus.ACTIVE
    }

    // 해시도 로그에 찍히지 않게
    override fun toString() = "ApiKey(id=$id, owner=$ownerType:$ownerId, name=$name, scopes=$scopes, tokenHash=<redacted>)"
}
