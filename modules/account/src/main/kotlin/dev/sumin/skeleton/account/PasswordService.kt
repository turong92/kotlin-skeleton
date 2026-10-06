package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes

/**
 * 비밀번호 재설정 · 변경.
 *
 * [forgot] 은 가입과 같은 방식으로 존재 여부를 숨긴다 — 요청 스레드는 한도와 캡차만 보고, 주소 조회 · 토큰 · 메일은 뒤에서 한다.
 * [reset] 은 메일함을 증명한 사람이 링크로 새 비밀번호를 정한다: 정책을 먼저 보고(실패해도 링크가 안 타게) 그다음 링크를 **원자적으로 소비**하고,
 * 모든 세션을 닫고 · 이메일을 확인된 것으로 처리하고 · 주인에게 알린다. [change] 는 현재 비밀번호를 요구하고(없는 계정은 이메일 확인이 요구) 다른 세션만 닫는다.
 */
class PasswordService(private val core: AccountCore) {
    fun forgot(email: String, ip: String?, captchaToken: String?) {
        ip?.let {
            val r = core.props.reset
            val a = core.limits.acquire("forgot:ip", it, r.perIp, r.perIpWindow)
            if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        }
        core.captcha.check(captchaToken, ip, "forgot_password")
        val normalized = Emails.normalize(email)
        core.tasks.run("password-forgot") { sendResetLink(normalized, ip) }
    }

    private fun sendResetLink(email: String, ip: String?) {
        val account = core.accountByEmail(email) ?: return
        if (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION) return
        val r = core.props.reset
        if (!core.limits.acquire("reset:email", email, r.perEmail, r.perEmailWindow).allowed) return
        val raw = core.tokens.issue(TokenPurposes.PASSWORD_RESET, email, account.id, r.ttl)
        core.mailer.send(AccountMail(MailKind.PASSWORD_RESET, email, account.locale, core.links.reset(raw), mapOf("minutes" to r.ttl.toMinutes().toString())))
        core.events.publish(AccountEventType.PASSWORD_RESET_REQUESTED, account.id, ip)
    }

    fun reset(token: String, newPassword: String) {
        val grant = core.tokens.peek(TokenPurposes.PASSWORD_RESET, token) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val account = grant.accountId?.let(core.accounts::findById)
        // 링크를 받은 뒤 주소가 바뀌었거나 계정이 막힌 · 사라진 경우 이 링크는 더 이상 주인을 증명하지 않는다
        if (account == null || account.email != grant.subject || (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION)) {
            throw AccountException(AccountErrorCode.TOKEN_INVALID)
        }
        val violations = core.policy.check(newPassword, account.email)
        if (violations.isNotEmpty()) throw PasswordPolicyException(violations)
        core.tokens.consume(TokenPurposes.PASSWORD_RESET, token) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)

        storePassword(account, core.hasher.hash(newPassword))
        core.accounts.markEmailVerified(account.id, core.time.now())
        core.sessions()?.revokeAll(account.id, null)
        core.closeSensitiveLinks(account)
        notifyChanged(account)
        core.events.publish(AccountEventType.PASSWORD_RESET, account.id)
        core.accounts.findById(account.id)?.let(core.bootstrap::afterVerified)
    }

    fun change(accountId: String, currentPassword: String?, newPassword: String, currentSessionId: String?, confirmationToken: String? = null) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        // 탈취된 세션으로 현재 비밀번호를 추측하지 못하게 — 맞든 틀리든 시도 수로 센다
        val login = core.props.login
        val a = core.limits.acquire("password-change:account", accountId, login.perAccount, login.window)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)

        val email = account.email
        val identity = email?.let { core.accounts.findIdentity(SignInMethods.PASSWORD, it) }
        if (identity?.secret == null && (!account.emailVerified || email == null)) throw AccountException(AccountErrorCode.PASSWORD_REQUIRED)
        // 비밀번호가 있으면 현재 비밀번호, 없으면(첫 비밀번호) 메일함 확인 — 15분짜리 액세스 토큰이 영구 자격이 되지 않게
        val proof = Reauth(core).check(account, currentPassword, confirmationToken)
        val violations = core.policy.check(newPassword, email)
        if (violations.isNotEmpty()) throw PasswordPolicyException(violations)
        proof.commit()

        storePassword(account, core.hasher.hash(newPassword))
        core.sessions()?.revokeAll(account.id, currentSessionId)
        core.closeSensitiveLinks(account)
        notifyChanged(account)
        core.events.publish(AccountEventType.PASSWORD_CHANGED, account.id)
    }

    /** 비밀번호 수단이 있으면 해시를 바꾸고, 없으면(소셜 · 매직 링크만 쓰던 계정) 새로 만든다 */
    private fun storePassword(account: Account, hash: String) {
        val email = requireNotNull(account.email) { "password needs an email" }
        val existing = core.accounts.findIdentity(SignInMethods.PASSWORD, email)
        if (existing != null) core.accounts.updateIdentitySecret(existing.id, hash)
        else core.accounts.addIdentity(Identity(core.newIdentityId(), account.id, SignInMethods.PASSWORD, email, verified = account.emailVerified, secret = hash, createdAt = core.time.now()))
    }

    private fun notifyChanged(account: Account) {
        account.email?.let { core.mailer.send(AccountMail(MailKind.PASSWORD_CHANGED, it, account.locale)) }
    }
}
