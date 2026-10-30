package dev.sumin.skeleton.apikey

import java.time.Instant

/** 메모리 저장소 대역 - 조건부 갱신(`touchLastUsed` · `revoke`)의 뜻은 JDBC 구현과 같게 쓴다 */
class FakeApiKeyStore : ApiKeyStore {
    private val rows = LinkedHashMap<String, ApiKey>()

    @Synchronized fun all(): List<ApiKey> = rows.values.toList()

    @Synchronized fun put(key: ApiKey) { rows[key.id] = key }

    @Synchronized override fun insert(key: ApiKey) {
        require(rows.values.none { it.tokenHash == key.tokenHash }) { "duplicate token hash" }
        rows[key.id] = key
    }

    @Synchronized override fun findById(id: String): ApiKey? = rows[id]

    @Synchronized override fun findByTokenHash(hash: String): ApiKey? = rows.values.firstOrNull { it.tokenHash == hash }

    @Synchronized override fun listByOwner(ownerType: String, ownerId: String): List<ApiKey> =
        rows.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }.sortedWith(compareByDescending<ApiKey> { it.createdAt }.thenByDescending { it.id })

    @Synchronized override fun page(ownerId: String?, status: ApiKeyStatus?, before: String?, limit: Int, now: Instant): List<ApiKey> =
        rows.values.filter { (ownerId == null || it.ownerId == ownerId) && (status == null || it.status(now) == status) && (before == null || it.id < before) }
            .sortedByDescending { it.id }.take(limit)

    @Synchronized override fun countActive(ownerType: String, ownerId: String, now: Instant): Int =
        rows.values.count { it.ownerType == ownerType && it.ownerId == ownerId && it.revokedAt == null && (it.expiresAt == null || it.expiresAt.isAfter(now)) }

    @Synchronized override fun revoke(id: String, by: String, reason: String?, now: Instant): Boolean {
        val key = rows[id]?.takeIf { it.revokedAt == null } ?: return false
        rows[id] = key.copy(revokedAt = now, revokedBy = by, revokedReason = reason)
        return true
    }

    @Synchronized override fun touchLastUsed(id: String, now: Instant, ip: String?, staleBefore: Instant): Boolean {
        val key = rows[id]?.takeIf { it.lastUsedAt == null || it.lastUsedAt.isBefore(staleBefore) } ?: return false
        rows[id] = key.copy(lastUsedAt = now, lastUsedIp = ip)
        return true
    }

    @Synchronized override fun deleteByOwner(ownerType: String, ownerId: String): Int {
        val gone = rows.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }.map { it.id }
        gone.forEach(rows::remove)
        return gone.size
    }
}
