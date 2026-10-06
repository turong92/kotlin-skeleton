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
    fun request(accountId: String, newEmail: String, currentPassword: String?, confirmationToken: String? = null) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val c = core.props.emailChange
        val a = core.limits.acquire("email-change:account", accountId, c.perAccount, c.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)

        val oldEmail = account.email
        // 비밀번호가 있으면 현재 비밀번호, 없으면 옛 주소의 메일함 확인 (주소가 아예 없는 계정은 면제 — 확인할 메일함이 없다)
        val proof = Reauth(core).check(account, currentPassword, confirmationToken)
        val target = Emails.normalize(newEmail)
        if (!Emails.plausible(target)) throw ApplicationException("Invalid email", PlatformErrorCode.VALIDATION_FAILED)
        proof.commit()

        // 대기 중인 변경은 요청 스레드에서 저장한다 — 202 직후의 `GET /me` 가 `pendingEmail` 을 바로 보여 준다.
        // 새 주소가 남의 것이든 아니든 똑같이 저장한다(요청자에게 보이는 상태가 주소의 사용 여부를 알려 주지 않게). 남의 주소로는 원문이 어디로도 가지 않으므로 확인할 수 없다.
        val raw = core.tokens.issue(TokenPurposes.EMAIL_CHANGE, account.id, account.id, c.ttl, payload = target)
        core.tasks.run("email-change-request") {
            // 옛 주소 알림은 새 주소가 쓰이는 중이든 아니든 똑같이 간다 — 로그인한 사용자의 받은편지함이 "그 주소는 가입돼 있다" 를 알려 주지 않게
            oldEmail?.let { core.mailer.send(AccountMail(MailKind.EMAIL_CHANGE_REQUESTED_NOTICE, it, account.locale)) }
            if (target == oldEmail || core.accountByEmail(target) != null) return@run
            core.mailer.send(AccountMail(MailKind.EMAIL_CHANGE_CONFIRM, target, account.locale, core.links.emailChange(raw), mapOf("minutes" to c.ttl.toMinutes().toString())))
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
