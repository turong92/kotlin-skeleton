package dev.sumin.skeleton.auth.handoff

import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.token.OneTimeTokens
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.common.account.WebHandoffIssuer
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("skeleton.auth-web-handoff")
data class WebHandoffProperties(
    /** 켜야 서 있다 - 기본은 꺼짐. 앱이 로그인한 채로 웹으로 넘어가는 길이 필요할 때만 켠다 */
    val enabled: Boolean = false,
    /** 코드 유효 시간 - 한 번 쓰고 나면 곧바로 죽는다. 브라우저를 여는 시간이면 되니 분 단위로 짧게 */
    val ttl: Duration = Duration.ofMinutes(2),
)

/**
 * 앱 -> 웹 넘어가기. 메일 링크 로그인과 **같은 토큰 장치**([OneTimeTokens]: 해시만 저장 · 원자적 한 번 · 만료 · 용도 구분)를 쓴다 - 주인(subject)은 계정 id 다.
 * 세션은 코드를 바꿀 때 `AuthTokenResponseFactory` 가 연다(막힌 계정 · 2단계 확인도 거기 그대로).
 */
class WebHandoff(
    private val tokens: OneTimeTokens,
    private val accounts: AuthAccountRepository,
    private val properties: WebHandoffProperties,
) : WebHandoffIssuer {
    override fun issue(accountId: String): String = tokens.issue(PURPOSE, accountId, accountId, properties.ttl)

    /** 코드를 소비해 로그인할 계정을 돌려준다. 쓸 수 없는 코드는 410 `ACCOUNT.TOKEN_INVALID` (원문은 메시지에 싣지 않는다) */
    fun redeem(code: String): AuthAccount {
        val grant = tokens.consume(PURPOSE, code) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        return grant.accountId?.let { accounts.findBy(AccountIdentifier(accountId = it)) } ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
    }

    /** 계정의 열린 코드를 모두 닫는다 */
    fun closeAll(accountId: String) { tokens.invalidate(PURPOSE, accountId) }

    companion object {
        const val PURPOSE = "web_handoff"
    }
}

/** 계정의 세션을 한꺼번에 닫는 모든 길(모든 기기 로그아웃 · 비밀번호 재설정 · 정지 · 탈퇴)이 세션 사건을 낸다 - 그때 남은 코드도 닫는다 */
class WebHandoffSessionListener(private val handoff: WebHandoff) : dev.sumin.skeleton.auth.session.SessionEventListener {
    override fun on(event: dev.sumin.skeleton.auth.session.SessionEvent) {
        if (event.type == dev.sumin.skeleton.auth.session.SessionEventType.SESSIONS_REVOKED) handoff.closeAll(event.accountId)
    }
}
