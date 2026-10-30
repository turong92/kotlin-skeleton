package dev.sumin.skeleton.auth.mfa

import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.Challenges
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.api.SecondFactorChallenge
import dev.sumin.skeleton.auth.api.SecondFactorGate

/**
 * `auth` 의 [SecondFactorGate] 구현 - 2단계가 켜진 계정이면 로그인 대기(`mfa_login`) 한 줄을 열고 `mfaToken` 을 낸다.
 * 대기는 새 표 없이 `account` 의 챌린지 표를 쓴다: id = mfaToken 의 SHA-256(원문은 저장하지 않는다), 코드 칸은 **어떤 코드로도 맞지 않는** 줄(코드가 아니라 로그인 한 건에 묶인 대기다).
 */
class TotpSecondFactorGate(
    private val store: MfaStore,
    private val challenges: Challenges,
    private val properties: MfaProperties,
) : SecondFactorGate {
    override fun enrolled(account: AuthAccount): Boolean = store.find(account.accountId)?.enabled == true

    override fun challenge(account: AuthAccount): SecondFactorChallenge? {
        if (!enrolled(account)) return null
        val opened = challenges.open(
            ChallengePurposes.MFA_LOGIN, account.accountId, properties.challenge.ttl, properties.challenge.maxAttempts,
            accountId = account.accountId, withHandle = true, dead = true,
        )
        return SecondFactorChallenge(opened.handle!!, listOf("totp", "recovery_code"), opened.row.expiresAt)
    }
}
