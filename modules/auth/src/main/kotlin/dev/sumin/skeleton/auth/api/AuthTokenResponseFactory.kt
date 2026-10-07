package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.session.LoginSessionIssuer
import dev.sumin.skeleton.auth.session.OpenedSession

/** 계정이 있지만 지금은 토큰을 낼 수 없다 (이메일 미확인 · 정지). 비밀번호가 맞은 뒤에만 던지므로 계정 존재를 새로 알려 주지 않는다 */
class AccountBlockedException(block: LoginBlock, data: Map<String, Any?>? = null) : dev.sumin.skeleton.common.ApplicationException(
    message = "Account cannot sign in: $block",
    errorCode = when (block) {
        LoginBlock.EMAIL_NOT_VERIFIED -> AuthErrorCode.EMAIL_NOT_VERIFIED
        LoginBlock.SUSPENDED -> AuthErrorCode.ACCOUNT_SUSPENDED
        LoginBlock.DELETION_PENDING -> AuthErrorCode.ACCOUNT_DELETION_PENDING
    },
    data = data,
)

/**
 * 모든 로그인 방법이 지나는 토큰 발급 한 곳. 막힌 계정은 여기서 거르고, [sessions] 가 있으면(`auth-session`) 세션을 열어
 * 세션 id 를 액세스 토큰(`sid`)과 응답에 싣는다 — 없으면 지금까지처럼 액세스 토큰만 낸다.
 */
class AuthTokenResponseFactory(
    private val jwtTokenService: JwtTokenService,
    private val sessions: () -> LoginSessionIssuer? = { null },
) {
    fun issue(account: AuthAccount): AuthTokenResponse {
        account.loginBlock?.let { throw AccountBlockedException(it, account.blockData?.invoke()) }
        return issueWith(account, sessions()?.open(account))
    }

    /** 이미 있는 세션으로 낸다 (refresh 경로 — 세션을 새로 열지 않는다) */
    fun issueWith(account: AuthAccount, session: OpenedSession?): AuthTokenResponse {
        account.loginBlock?.let { throw AccountBlockedException(it, account.blockData?.invoke()) }
        val principal = account.toCurrentPrincipal(session?.sessionId)
        val token = jwtTokenService.issue(principal)
        return AuthTokenResponse(
            accessToken = token.accessToken,
            expiresAt = token.expiresAt,
            principal = principal,
            refreshToken = session?.refreshToken,
            refreshExpiresAt = session?.refreshExpiresAt,
            sessionId = session?.sessionId,
        )
    }

    private fun AuthAccount.toCurrentPrincipal(sessionId: String?): CurrentPrincipal =
        CurrentPrincipal(
            accountId = accountId,
            username = username,
            email = email,
            roles = roles,
            sessionId = sessionId,
        )
}
