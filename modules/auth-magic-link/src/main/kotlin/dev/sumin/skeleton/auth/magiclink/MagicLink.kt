package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountErrorCode
import dev.sumin.skeleton.account.AccountException
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Emails
import dev.sumin.skeleton.account.SignInMethods
import dev.sumin.skeleton.account.abuse.AccountRateLimits
import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.auth.account.AuthAccount
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("skeleton.auth-magic-link")
data class MagicLinkProperties(
    /** 링크 유효 시간 — 한 번 쓰고 나면 곧바로 죽는다 */
    val ttl: Duration = Duration.ofMinutes(15),
    /** 링크를 처음 쓰는 이메일에 계정을 **만들까**. 기본은 아니오 — 이미 있는 계정으로만 들어온다. 켜면 메일함 증명만으로 가입이 끝난다 */
    val signUp: Boolean = false,
    /** 한 이메일에 보낼 수 있는 링크 수(창 안) — 넘으면 조용히 안 보낸다 */
    val perEmail: Int = 3,
    val perEmailWindow: Duration = Duration.ofHours(1),
    val perIp: Int = 10,
    val perIpWindow: Duration = Duration.ofHours(1),
    val http: Http = Http(),
) {
    data class Http(
        /** false 면 `/api/v1/auth/magic-link/request` · `/redeem` 를 등록하지 않는다 */
        val enabled: Boolean = true,
    )
}

/** 매직 링크 수단 — 증명이 곧 메일함 소유라서 같은 이메일의 기존 계정에 붙고, 이메일은 확인된 것으로 친다 */
class MagicLinkSignInMethod : SignInMethod {
    override val code: String = SignInMethods.MAGIC_LINK
    override fun normalize(subject: String): String = Emails.normalize(subject)
    override val provesEmail: Boolean = true
    override val exposesSubject: Boolean = true
}

/**
 * 메일로 보낸 한 번 쓰는 링크로 로그인. 인증 · 재설정과 **같은 토큰 장치**([dev.sumin.skeleton.account.token.OneTimeTokens])를 쓴다 —
 * 해시만 저장, 한 번만, 용도가 다르면 못 쓴다. 요청은 계정 존재 여부와 무관하게 늘 같은 응답(조회 · 메일은 뒤에서)이고,
 * 링크를 열기만 해서는 아무것도 일어나지 않는다 — 프론트가 버튼(또는 SPA 라우트)에서 [redeem] 을 POST 한다 (메일 보안 검사기가 GET 을 미리 열어도 링크가 안 타게).
 */
class MagicLinkService(
    private val core: AccountCore,
    private val signIn: AccountSignInService,
    private val props: MagicLinkProperties,
) {
    fun request(email: String, ip: String?, captchaToken: String?) {
        core.captcha.check(captchaToken, ip, "magic_link")
        ip?.let {
            val a = core.limits.acquire("magic-link:ip", it, props.perIp, props.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        val normalized = Emails.normalize(email)
        core.tasks.run("magic-link-request") {
            val account = core.accountByEmail(normalized)
            if (account?.status == AccountStatus.DELETED) return@run
            if (account == null && !props.signUp) return@run
            if (!core.limits.acquire("magic-link:email", normalized, props.perEmail, props.perEmailWindow).allowed) return@run
            val raw = core.tokens.issue(TokenPurposes.MAGIC_LINK, normalized, account?.id, props.ttl)
            core.mailer.send(AccountMail(MailKind.MAGIC_LINK, normalized, account?.locale, core.links.magicLink(raw), mapOf("minutes" to props.ttl.toMinutes().toString())))
            core.events.publish(AccountEventType.MAGIC_LINK_REQUESTED, account?.id, ip)
        }
    }

    /** 링크를 소비해 로그인할 계정을 돌려준다 (토큰 발급 · 세션은 호출자가 `AuthTokenResponseFactory` 로). 쓸 수 없는 링크는 410 */
    fun redeem(token: String, ip: String?): AuthAccount {
        val grant = core.tokens.consume(TokenPurposes.MAGIC_LINK, token) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val email = grant.subject
        return signIn.signIn(SignInProof(SignInMethods.MAGIC_LINK, email, email, emailVerified = true, ip = ip, allowSignUp = props.signUp))
            ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
    }
}
