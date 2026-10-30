package dev.sumin.skeleton.auth.mfa

import java.time.Instant

/** 시험용 메모리 저장소 - 조건부 갱신은 `synchronized` 로 같은 뜻을 낸다(진짜 SQL 은 `auth-mfa-jdbc` 의 dbTest 가 두 방언에서 본다) */
class FakeMfaStore : MfaStore {
    private val rows = HashMap<String, MfaTotpRecord>()
    private val codes = HashMap<String, MutableMap<String, Boolean>>()   // account -> hash -> used

    @Synchronized override fun find(accountId: String): MfaTotpRecord? = rows[accountId]

    @Synchronized override fun startEnrollment(accountId: String, pendingSecretEnc: String, expiresAt: Instant, now: Instant) {
        val row = rows[accountId] ?: MfaTotpRecord(accountId, null, null, null, null, null, now)
        rows[accountId] = row.copy(pendingSecretEnc = pendingSecretEnc, pendingExpiresAt = expiresAt, updatedAt = now)
    }

    @Synchronized override fun activate(accountId: String, expectedPendingEnc: String, step: Long, now: Instant, recoveryCodeHashes: List<String>?): Boolean {
        val row = rows[accountId] ?: return false
        if (row.pendingSecretEnc != expectedPendingEnc || row.pendingExpiresAt?.isAfter(now) != true) return false
        rows[accountId] = row.copy(secretEnc = row.pendingSecretEnc, enabledAt = now, lastUsedStep = step, pendingSecretEnc = null, pendingExpiresAt = null, updatedAt = now)
        recoveryCodeHashes?.let { replaceRecoveryCodes(accountId, it, now) }
        return true
    }

    @Synchronized override fun useStep(accountId: String, step: Long, now: Instant): Boolean {
        val row = rows[accountId]?.takeIf { it.secretEnc != null } ?: return false
        if (row.lastUsedStep != null && row.lastUsedStep >= step) return false
        rows[accountId] = row.copy(lastUsedStep = step, updatedAt = now)
        return true
    }

    @Synchronized override fun replaceRecoveryCodes(accountId: String, hashes: List<String>, now: Instant) {
        codes[accountId] = hashes.associateWith { false }.toMutableMap()
    }

    @Synchronized override fun useRecoveryCode(accountId: String, hash: String, now: Instant): Boolean {
        val book = codes[accountId] ?: return false
        if (book[hash] != false) return false
        book[hash] = true
        return true
    }

    @Synchronized override fun recoveryCodesRemaining(accountId: String): Int = codes[accountId]?.count { !it.value } ?: 0

    @Synchronized override fun deleteAll(accountId: String) {
        rows.remove(accountId)
        codes.remove(accountId)
    }

    /** 값 훑기용 - 저장된 모든 것을 한 문자열로 */
    @Synchronized fun dump(): String = rows.values.joinToString("|") { "${it.secretEnc}/${it.pendingSecretEnc}" } + "|" + codes.values.flatMap { it.keys }.joinToString("|")
}
