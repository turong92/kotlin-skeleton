package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType

/**
 * **등록 미완료 계정을 본인이 즉시 버린다** — `email.required=true` 인데 주소를 한 번도 가진 적 없어서 아무 기능도 못 쓴 계정.
 * 쓰임: 이메일로 가입해 둔 사람이 Kakao 로 들어와 주소 없는 새 계정이 생겼다 → 자기 주소를 넣었더니 "이미 계정이 있어요" → 기존 계정에 로그인해 Kakao 를 연결하려는데 Kakao 가 껍데기 계정에 붙어 있다.
 * 일반 탈퇴(유예 30일)로는 그동안 수단이 잡혀 있으니, 이 계정만은 **유예 없이** 지운다 — 같은 지우기 길([AccountPurgeService], 다른 모듈의 `AccountErasureListener` 포함, `deletion.mode` 대로 ERASED 행 또는 삭제)을 곧바로 탄다.
 *
 * 조건(어긋나면 409 `ACCOUNT.DISCARD_NOT_ALLOWED`): `email.required=true` · 계정에 주소 없음 · ACTIVE. **정지 계정은 못 한다**(정지는 박제 — 버려서 빠져나가지 못한다). 차단 해시는 건드리지 않는다
 * (운영자가 지운 계정의 재가입 차단은 그대로이고, 버린 뒤 같은 소셜로 다시 들어오면 차단·가입 정책을 새 가입처럼 다시 지난다 — 이 경로로 얻는 것은 없다).
 * 재인증: 일반 탈퇴와 같다 — 단 **방금 로그인한 그 세션**이면 면제([AccountCore.freshLoginSession], 첫 이메일 등록과 같은 창). 옛 세션은 제공자 새 인가 코드.
 * 마지막 관리자는 못 버린다(409 `ACCOUNT.LAST_ADMIN`). 다른 모듈의 지우기가 실패하면 계정은 DELETED(즉시 정리 대상)로 남고 503 `ACCOUNT.ERASURE_RETRY` — 같은 호출을 되풀이하거나 정리 잡이 끝낸다.
 */
class DiscardService(private val core: AccountCore, private val purge: AccountPurgeService) {
    fun discard(accountId: String, input: ReauthInput, sessionId: String?) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val now = core.time.now()
        // 반쯤 끝난 버리기의 되풀이 — 이미 DELETED 이고 유예가 없는(지금 정리 대상) 주소 없는 계정. 끝내기만 하므로 다시 인증하지 않는다
        val retry = account.status == AccountStatus.DELETED && account.email == null && account.purgeAfter?.isAfter(now) == false
        if (!retry) {
            if (!core.props.email.required || account.email != null || account.status != AccountStatus.ACTIVE) throw AccountException(AccountErrorCode.DISCARD_NOT_ALLOWED)
            val via = begin(account, input, sessionId)
            core.events.publish(AccountEventType.ACCOUNT_DISCARDED, accountId, detail = mapOf("via" to via))
        }
        if (!purge.purgeNow(accountId) && core.accounts.findById(accountId)?.let { it.status != AccountStatus.ERASED } == true) throw AccountException(AccountErrorCode.ERASURE_RETRY)
    }

    /** 다시 인증을 거쳐 DELETED(지금 정리 대상)로 옮기고 세션을 모두 닫는다. 돌려주는 값은 인증 방식(감사용) */
    private fun begin(account: Account, input: ReauthInput, sessionId: String?): String {
        val fresh = core.freshLoginSession(account, sessionId)
        val proof = if (fresh) Reauth.Proof.NONE else {
            val login = core.props.login
            val allowance = core.limits.acquire("reauth:account", account.id, login.perAccount, login.window)
            if (!allowance.allowed) throw RateLimitedException(allowance.retryAfterSeconds)
            Reauth(core).check(account, input, sessionId, passwordFailure = AccountErrorCode.REAUTH_FAILED)
        }
        val now = core.time.now()
        when (core.accounts.updateUnlessLast(account.id, AccountPatch(status = AccountStatus.DELETED, deletedAt = now, purgeAfter = now), now, core.props.admin.role)) {
            GuardedResult.LAST -> throw AccountException(AccountErrorCode.LAST_ADMIN)
            GuardedResult.NOT_FOUND -> throw AccountException(AccountErrorCode.NOT_FOUND)
            GuardedResult.DONE -> Unit
        }
        proof.commit()
        core.sessions()?.revokeAll(account.id, null)
        return if (fresh) "fresh_login" else "reauth"
    }
}
