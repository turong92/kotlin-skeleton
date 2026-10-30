package dev.sumin.skeleton.apikey

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.authz.OperatorRef
import dev.sumin.skeleton.common.id.SortableIds
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant

/** 발급 결과 - [token] 은 이 값에만 있다(저장 · 로그 · 이벤트에 없다) */
class CreatedApiKey(val key: ApiKey, val token: String) {
    override fun toString() = "CreatedApiKey(key=$key, token=<redacted>)"
}

/**
 * 키 발급 · 내 목록 · 본인 회수. **다시 인증은 호출 쪽(컨트롤러)이 이 앞에서 한다** - 정책 검사 같은 실패가 비밀번호 확인을 태우지 않게.
 * 키의 주인은 호출자 계정이다(1차). 다른 소유자 종류는 [ApiKeyOwnerPolicy] 가 맡는다.
 */
class ApiKeys(
    private val store: ApiKeyStore,
    private val scopes: ApiKeyScopes,
    private val policies: () -> List<ApiKeyOwnerPolicy>,
    private val properties: ApiKeyProperties,
    private val time: TimeProvider,
    private val tokens: ApiKeyTokens,
    private val ids: SortableIds = SortableIds(),
) {
    fun create(caller: OperatorRef, name: String, requestedScopes: Collection<String>, expiresAt: Instant?): CreatedApiKey {
        val owner = ApiKeyOwner(ApiKeyOwnerTypes.ACCOUNT, caller.accountId)
        if (!canManage(caller, owner)) throw ApplicationException("API keys cannot be issued for this owner", PlatformErrorCode.FORBIDDEN)

        val cleanName = name.trim().takeIf { it.isNotEmpty() } ?: throw FieldValidationException("name", "Required", "name is required")
        val chosen = requestedScopes.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (chosen.isEmpty()) throw FieldValidationException("scopes", "Required", "at least one scope is required")
        chosen.firstOrNull { !scopes.isDeclared(it) }?.let { throw ApiKeyException(ApiKeyErrorCode.SCOPE_UNKNOWN, "Unknown scope: $it", data = mapOf("scope" to it)) }
        if (chosen.joinToString(" ").length > SCOPES_COLUMN) throw FieldValidationException("scopes", "Size", "too many scopes")

        val now = time.now()
        val expiry = expiresAt?.let { TimeProvider.truncateToDatabasePrecision(it) }
        checkExpiry(expiry, now)
        if (store.countActive(owner.type, owner.id, now) >= properties.maxActivePerOwner) {
            throw ApiKeyException(ApiKeyErrorCode.LIMIT_REACHED, data = mapOf("max" to properties.maxActivePerOwner))
        }

        val generated = tokens.generate()
        val key = ApiKey(
            id = ids.newId(ID_PREFIX), ownerType = owner.type, ownerId = owner.id, createdBy = caller.accountId, name = cleanName,
            tokenHash = generated.hash, tokenHint = generated.hint, scopes = chosen.toSet(), expiresAt = expiry,
            revokedAt = null, revokedBy = null, revokedReason = null, lastUsedAt = null, lastUsedIp = null, createdAt = TimeProvider.truncateToDatabasePrecision(now),
        )
        store.insert(key)
        return CreatedApiKey(key, generated.token)
    }

    fun listOf(accountId: String): List<ApiKey> = store.listByOwner(ApiKeyOwnerTypes.ACCOUNT, accountId)

    /** 남의 키 · 없는 키는 둘 다 404 - 키가 있는지 알려 주지 않는다. 이미 회수된 키는 그대로 성공(멱등) */
    fun revoke(caller: OperatorRef, id: String) {
        val key = store.findById(id)?.takeIf { canManage(caller, ApiKeyOwner(it.ownerType, it.ownerId)) } ?: throw ApiKeyException(ApiKeyErrorCode.NOT_FOUND)
        store.revoke(key.id, caller.accountId, null, time.now())
    }

    fun declaredScopes(): List<DeclaredApiKeyScope> = scopes.all()

    private fun canManage(caller: OperatorRef, owner: ApiKeyOwner) = policies().firstOrNull { it.ownerType == owner.type }?.canManage(caller, owner) == true

    private fun checkExpiry(expiresAt: Instant?, now: Instant) {
        val max = properties.maxLifetime
        val bad = when {
            expiresAt == null -> max != null
            !expiresAt.isAfter(now) -> true
            else -> max != null && expiresAt.isAfter(now.plus(max))
        }
        if (bad) throw ApiKeyException(ApiKeyErrorCode.EXPIRES_INVALID, data = max?.let { mapOf("maxLifetime" to it.toString()) })
    }

    private companion object {
        const val ID_PREFIX = "key_"
        const val SCOPES_COLUMN = 1000
    }
}
