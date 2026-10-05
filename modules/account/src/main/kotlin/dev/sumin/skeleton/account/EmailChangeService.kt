package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.events.AccountEventType
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.PlatformErrorCode

/**
 * 이메일 변경 — **새 주소를 확인하기 전에는 아무것도 바뀌지 않는다.** 탈취된 세션이 주소를 바꿔 계정을 가로채는 것을 막는 두 겹:
 * 비밀번호가 있는 계정은 현재 비밀번호를 요구하고, 옛 주소에는 요청 · 변경 두 번 알림이 간다 (옛 주소의 주인이 비밀번호를 바꿔 되돌릴 수 있다).
 * 새 주소가 남의 것이어도 응답은 같고 아무것도 보내지 않는다 (주소가 쓰이고 있는지 알려 주지 않는다).
 */
class EmailChangeService(private val core: AccountCore) {
    fun request(accountId: String, newEmail: String, currentPassword: String?) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val c = core.props.emailChange
        val a = core.limits.acquire("email-change:account", accountId, c.perAccount, c.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)

        val oldEmail = account.email
        val identity = oldEmail?.let { core.accounts.findIdentity(SignInMethods.PASSWORD, it) }
        if (identity?.secret != null && (currentPassword == null || !core.hasher.matches(currentPassword, identity.secret!!))) {
            throw AccountException(AccountErrorCode.CURRENT_PASSWORD_INVALID)
        }
        val target = Emails.normalize(newEmail)
        if (!Emails.plausible(target)) throw ApplicationException("Invalid email", PlatformErrorCode.VALIDATION_FAILED)

        core.tasks.run("email-change-request") {
            if (target == oldEmail || core.accounts.findByEmail(target) != null) return@run
            val raw = core.tokens.issue(TokenPurposes.EMAIL_CHANGE, account.id, account.id, c.ttl, payload = target)
            core.mailer.send(AccountMail(MailKind.EMAIL_CHANGE_CONFIRM, target, account.locale, core.links.emailChange(raw), mapOf("minutes" to c.ttl.toMinutes().toString())))
            oldEmail?.let { core.mailer.send(AccountMail(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE, it, account.locale)) }
            core.events.publish(AccountEventType.EMAIL_CHANGE_REQUESTED, account.id)
        }
    }

    fun confirm(token: String) {
        val grant = core.tokens.consume(TokenPurposes.EMAIL_CHANGE, token) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val newEmail = grant.payload ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val account = grant.accountId?.let(core.accounts::findById) ?: throw AccountException(AccountErrorCode.TOKEN_INVALID)
        if (account.status != AccountStatus.ACTIVE && account.status != AccountStatus.PENDING_VERIFICATION) throw AccountException(AccountErrorCode.TOKEN_INVALID)
        val oldEmail = account.email
        when (core.accounts.changeEmail(account.id, newEmail, core.time.now())) {
            ChangeEmailResult.TAKEN -> throw AccountException(AccountErrorCode.EMAIL_TAKEN)
            ChangeEmailResult.NOT_FOUND -> throw AccountException(AccountErrorCode.TOKEN_INVALID)
            ChangeEmailResult.CHANGED -> Unit
        }
        core.sessions()?.revokeAll(account.id, null)
        oldEmail?.let { core.mailer.send(AccountMail(MailKind.EMAIL_CHANGED_NOTICE, it, account.locale)) }
        core.events.publish(AccountEventType.EMAIL_CHANGED, account.id)
        core.accounts.findById(account.id)?.let(core.bootstrap::afterVerified)
    }
}
