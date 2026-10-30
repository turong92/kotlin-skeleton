package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.LoginBlock
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.auth.session.LoginSessionIssuer
import dev.sumin.skeleton.auth.session.OpenedSession
import dev.sumin.skeleton.common.authz.OperatorAccess

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
 * [secondFactorGates] 가 있으면 막힌 계정을 거른 다음, 두 번째 단계가 켜진 계정에는 **세션도 토큰도 내지 않고** [SecondFactorRequiredException] 을 던진다
 * (두 번째 단계를 마친 뒤에는 [issueAfterSecondFactor]). 고리가 비면 아무것도 달라지지 않는다.
 */
class AuthTokenResponseFactory(
    private val jwtTokenService: JwtTokenService,
    private val operatorAccess: () -> OperatorAccess? = { null },
    private val secondFactorGates: () -> List<SecondFactorGate> = { emptyList() },
    private val sessions: () -> LoginSessionIssuer? = { null },
) {
    fun issue(account: AuthAccount): AuthTokenResponse {
        account.loginBlock?.let { throw AccountBlockedException(it, account.blockData?.invoke()) }
        secondFactorGates().firstNotNullOfOrNull { it.challenge(account) }?.let { throw SecondFactorRequiredException(it) }
        return issueWith(account, sessions()?.open(account))
    }

    /** 두 번째 단계를 마친 로그인 — 고리를 다시 묻지 않고 [issue] 와 같은 세션 · 토큰을 낸다 (막힌 계정은 그동안 막혔을 수 있어 다시 거른다) */
    fun issueAfterSecondFactor(account: AuthAccount): AuthTokenResponse {
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
            principal = principal.withPermissions(operatorAccess()),
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
