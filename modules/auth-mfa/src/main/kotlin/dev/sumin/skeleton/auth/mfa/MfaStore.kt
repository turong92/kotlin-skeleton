package dev.sumin.skeleton.auth.mfa

import java.time.Instant

/**
 * 계정당 한 줄 — 켜짐 = [secretEnc] 있음. [secretEnc] · [pendingSecretEnc] 는 `crypto` 의 `TextEncryptor` 봉투(`aes-gcm:v1:…`)다 - 이 모듈 밖에서는 읽을 수 없다.
 * 재등록은 `pending_*` 에 두었다가 확인 때 한 문장으로 옮긴다(옛 비밀은 새 것이 확인될 때까지 산다).
 */
data class MfaTotpRecord(
    val accountId: String,
    val secretEnc: String?,
    val enabledAt: Instant?,
    val lastUsedStep: Long?,
    val pendingSecretEnc: String?,
    val pendingExpiresAt: Instant?,
    val updatedAt: Instant,
) {
    val enabled: Boolean get() = secretEnc != null

    override fun toString() = "MfaTotpRecord(accountId=$accountId, enabled=$enabled, enabledAt=$enabledAt, lastUsedStep=$lastUsedStep, pending=${pendingSecretEnc != null}, secrets=<redacted>)"
}

/**
 * 2단계 저장 포트. 구현은 `auth-mfa-jdbc` 가 낸다 - 메모리 구현은 없다(재시작에 비밀 · 복구 코드가 사라지면 안 된다).
 * [activate] · [useStep] · [useRecoveryCode] 는 **한 문장의 조건부 갱신**이라 동시에 와도 한 요청만 이긴다.
 */
interface MfaStore {
    fun find(accountId: String): MfaTotpRecord?

    /** 등록을 시작한다 - 이미 켜져 있으면 옛 비밀은 그대로 두고 `pending_*` 만 새것으로 덮는다(이전 대기는 버려진다) */
    fun startEnrollment(accountId: String, pendingSecretEnc: String, expiresAt: Instant, now: Instant)

    /**
     * 대기 중인 비밀이 [expectedPendingEnc] 이고 [now] 에 아직 만료되지 않았으면 그것을 비밀로 올린다(`last_used_step` = [step] - 확인에 쓴 코드는 다시 못 쓴다).
     * [recoveryCodeHashes] 가 있으면(첫 등록) 같은 트랜잭션에서 복구 코드를 그 해시들로 바꾼다. 올렸으면 true.
     */
    fun activate(accountId: String, expectedPendingEnc: String, step: Long, now: Instant, recoveryCodeHashes: List<String>?): Boolean

    /** 같은 걸음(또는 더 옛 걸음)의 코드를 두 번 받지 않는다 - `last_used_step < step` 일 때만 [step] 을 적고 true (RFC 6238 §5.2) */
    fun useStep(accountId: String, step: Long, now: Instant): Boolean

    /** 복구 코드를 지우고 [hashes] 로 새로 둔다(재발급) */
    fun replaceRecoveryCodes(accountId: String, hashes: List<String>, now: Instant)

    /** 아직 안 쓴 복구 코드면 쓴 것으로 표시하고 true - 같은 코드를 동시에 두 요청이 써도 하나만 */
    fun useRecoveryCode(accountId: String, hash: String, now: Instant): Boolean

    fun recoveryCodesRemaining(accountId: String): Int

    /** 끄기 · 운영자 해제 · 탈퇴 - 비밀과 복구 코드를 모두 지운다(멱등) */
    fun deleteAll(accountId: String)
}
