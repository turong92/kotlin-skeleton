package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.token.TokenPurposes

/**
 * 민감한 일(이메일 변경 · 첫 비밀번호 · 로그인 수단 연결) 앞의 **다시 인증**.
 * 비밀번호가 있는 계정은 현재 비밀번호, 없는 계정은 메일함 확인 링크([requestConfirmation])의 토큰 — 15분짜리 액세스 토큰 하나가
 * 영구 자격(바뀐 주소 · 새 비밀번호 · 붙은 소셜)으로 바뀌지 못하게 한다. 주소가 없는 계정(이메일 없는 소셜 가입)은 확인할 메일함이 없어 면제된다.
 */
class Reauth(private val core: AccountCore) {
    /** [check] 가 통과한 뒤 일이 끝까지 가면 [commit] — 정책 검사 같은 실패가 확인 링크를 태우지 않게 둘로 나눈다 */
    class Proof internal constructor(private val onCommit: () -> Unit) {
        fun commit() = onCommit()

        companion object { val NONE = Proof {} }
    }

    /** 현재 주소로 한 번 쓰는 확인 링크를 보낸다 (응답은 늘 같다 — 주소가 없으면 조용히) */
    fun requestConfirmation(accountId: String) {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val c = core.props.emailChange
        val a = core.limits.acquire("reauth-confirmation:account", accountId, c.perAccount, c.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        val email = account.email ?: return
        core.tasks.run("reauth-confirmation") {
            val raw = core.tokens.issue(TokenPurposes.REAUTH, accountId, accountId, c.ttl)
            core.mailer.send(AccountMail(MailKind.REAUTH_CONFIRM, email, account.locale, core.links.reauth(raw), mapOf("minutes" to c.ttl.toMinutes().toString())))
        }
    }

    /** 비밀번호가 있으면 [currentPassword], 없으면 [confirmationToken]. 통과하면 [Proof] — 일을 마친 뒤 `commit()` */
    fun check(account: Account, currentPassword: String?, confirmationToken: String?): Proof {
        val email = account.email
        val hash = email?.let { core.accounts.findIdentity(SignInMethods.PASSWORD, it)?.secret }
        if (hash != null) {
            if (currentPassword == null || !core.hasher.matches(currentPassword, hash)) throw AccountException(AccountErrorCode.CURRENT_PASSWORD_INVALID)
            return Proof.NONE
        }
        if (email == null) return Proof.NONE
        if (confirmationToken == null) throw AccountException(AccountErrorCode.REAUTH_REQUIRED)
        val grant = core.tokens.peek(TokenPurposes.REAUTH, confirmationToken)
        if (grant == null || grant.accountId != account.id) throw AccountException(AccountErrorCode.REAUTH_FAILED)
        return Proof {
            if (core.tokens.consume(TokenPurposes.REAUTH, confirmationToken)?.accountId != account.id) throw AccountException(AccountErrorCode.REAUTH_FAILED)
        }
    }
}
