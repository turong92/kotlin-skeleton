package dev.sumin.skeleton.auth.session

import dev.sumin.skeleton.auth.account.AuthAccount
import java.time.Instant

/** 로그인이 연 세션 — [refreshToken] 은 body 전달일 때만 채워진다 (쿠키 전달이면 발급자가 쿠키로 내려 보내고 null). */
data class OpenedSession(
    val sessionId: String,
    val refreshToken: String? = null,
    val refreshExpiresAt: Instant? = null,
) {
    // 리프레시 토큰이 로그에 찍히지 않게
    override fun toString() = "OpenedSession(sessionId=$sessionId, refreshToken=${if (refreshToken == null) "none" else "<redacted>"}, refreshExpiresAt=$refreshExpiresAt)"
}

/**
 * 로그인(비밀번호 · 소셜 · 매직 링크 …)이 성공해 액세스 토큰을 내기 직전에 세션을 연다. `auth-session` 이 구현하고,
 * 없으면 `auth` 는 지금까지처럼 액세스 토큰만 발급한다. 어떤 로그인 방법이든 [dev.sumin.skeleton.auth.api.AuthTokenResponseFactory] 를
 * 지나므로 세션은 한 곳에서만 만들어진다.
 */
interface LoginSessionIssuer {
    fun open(account: AuthAccount): OpenedSession
}

/** 계정의 세션을 한꺼번에 닫는다 — 비밀번호 재설정 · 정지 · 삭제가 쓴다. `auth-session` 이 구현한다. */
interface SessionRevoker {
    /** [exceptSessionId] 의 세션은 남긴다 (비밀번호 변경 — 지금 쓰는 기기는 로그인 상태로) */
    fun revokeAll(accountId: String, exceptSessionId: String?)

    fun revokeAll(accountId: String) = revokeAll(accountId, null)
}
