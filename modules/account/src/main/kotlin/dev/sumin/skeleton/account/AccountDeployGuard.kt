package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.token.InMemoryOneTimeTokenStore
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/**
 * stage · prod(그리고 auth 의 보호 프로필)에서 계정이 **믿을 수 없는 구성**으로 서는 것을 막는다. 메시지에는 속성 · 모듈 이름만 쓴다 (값 없음).
 * 새 가드라서 판단은 가드 몫이다 — [DeployContext.protectedBy] 에 auth 의 보호 프로필 목록을 함께 넘겨 auth 가드와 같은 환경을 같게 본다.
 */
class AccountDeployGuard(
    private val props: AccountProperties,
    private val protectedProfiles: List<String>,
    private val state: () -> State,
) : DeployGuard {
    /** 가드가 돌 때 읽는 구성 상태 — 빈 생성 순서에 기대지 않는다 */
    data class State(
        val repository: AccountRepository?,
        val tokenStore: Any?,
        val mailTransportIsLogOnly: Boolean,
        val captchaAvailable: Boolean,
    )

    override val name: String = "account"

    override fun problems(context: DeployContext): List<String> = buildList {
        if (!context.protectedBy(protectedProfiles)) return@buildList
        val s = state()
        if (s.repository is InMemoryAccountRepository) add("account uses the in-memory AccountRepository (every account vanishes on restart): add modules:account-jdbc or define your own AccountRepository bean")
        if (s.tokenStore is InMemoryOneTimeTokenStore) add("account uses the in-memory OneTimeTokenStore (emailed links stop working on restart and across instances): add modules:account-jdbc or define your own OneTimeTokenStore bean")
        if (s.mailTransportIsLogOnly) add("account has no mail transport, so verification and reset links cannot be delivered: add modules:notification-mail (skeleton.notification-mail.enabled, from, spring.mail.host) or define an AccountMailTransport bean")
        if (props.mail.linkBaseUrl.isBlank()) add("skeleton.account.mail.link-base-url is empty: set the frontend origin the mailed links should open")
        if (props.mail.logLinks == AccountProperties.Mail.LogLinks.ON) add("skeleton.account.mail.log-links=ON writes one-time link tokens to the log: remove it outside local")
        if (props.seed.accounts.isNotEmpty()) add("skeleton.account.seed.accounts defines seed accounts (known passwords): remove them outside local")
        if (props.captcha.required && !s.captchaAvailable) add("skeleton.account.captcha.required=true but no captcha verifier exists: add modules:captcha-turnstile and enable it, or set it to false")
        if (props.bootstrap.adminEmail.isNotBlank() && !Emails.plausible(Emails.normalize(props.bootstrap.adminEmail))) add("skeleton.account.bootstrap.admin-email is not an email address")
    }

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (!context.protectedBy(protectedProfiles)) return@buildList
        if (!props.login.throttleEnabled) add("skeleton.account.login.throttle-enabled=false: sign-in brute force is not limited by this module")
        if (props.signUp.enabled && !props.captcha.required) add("sign-up is open without a captcha (skeleton.account.captcha.required=false): bots can create accounts and mail strangers")
        if (props.admin.enabled && props.bootstrap.adminEmail.isBlank()) add("the admin API is enabled but no administrator can exist yet: set skeleton.account.bootstrap.admin-email or grant ADMIN another way")
        if (!props.signUp.emailVerification) add("sign-up works without email verification (skeleton.account.sign-up.email-verification=false): duplicate addresses are visible as 409")
    }
}
