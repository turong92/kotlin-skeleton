package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.common.ApplicationException
import java.time.Instant

/**
 * 두 번째 단계가 열렸다 — [mfaToken] 으로만 다음 걸음(`auth-mfa` 의 `POST /auth/mfa/verify`)을 이어 간다.
 * [mfaToken] 은 한 번의 로그인에 묶인 비밀이라 **로그 · 이벤트에 싣지 않는다** — 구현은 저장소에 해시만 둔다.
 * [methods] 는 이 계정이 쓸 수 있는 방법 코드(예: `totp` · `recovery_code`).
 */
data class SecondFactorChallenge(val mfaToken: String, val methods: List<String>, val expiresAt: Instant) {
    override fun toString() = "SecondFactorChallenge(mfaToken=<redacted>, methods=$methods, expiresAt=$expiresAt)"
}

/**
 * 로그인의 두 번째 단계 고리 — 빈으로 등록하면 [AuthTokenResponseFactory.issue](**모든** 로그인 방법이 지나는 한 곳)가 묻는다.
 * 비어 있으면 로그인은 지금과 같다. 켜진 계정이면 대기를 열고 [SecondFactorChallenge] 를, 아니면 null 을 돌려준다
 * (패스키 같은 다른 구현이 나란히 올 수 있다 — 먼저 대기를 연 고리가 이긴다).
 */
fun interface SecondFactorGate {
    fun challenge(account: AuthAccount): SecondFactorChallenge?
}

/** 첫 번째 단계(비밀번호 · 소셜 · 매직 링크 · 탈퇴 취소)는 통과했지만 토큰은 두 번째 단계 뒤에 나간다 — `403 AUTH.MFA_REQUIRED` + `data{mfaToken, methods, expiresAt}` */
class SecondFactorRequiredException(challenge: SecondFactorChallenge) : ApplicationException(
    message = "Second factor required",
    errorCode = AuthErrorCode.MFA_REQUIRED,
    data = mapOf("mfaToken" to challenge.mfaToken, "methods" to challenge.methods, "expiresAt" to challenge.expiresAt),
)
