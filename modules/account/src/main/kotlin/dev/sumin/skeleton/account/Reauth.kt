package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.RateLimitedException
import dev.sumin.skeleton.account.challenge.ChallengePurposes
import dev.sumin.skeleton.account.challenge.CodeCheck
import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.MailKind

/** 이메일이 없는 계정의 다시 인증 — **지금 새로 받은** 인가 코드 하나. 이미 이 계정에 연결된 제공자의 것이어야 한다 */
data class SocialReauth(val provider: String, val authorizationCode: String, val redirectUri: String? = null, val codeVerifier: String? = null, val nonce: String? = null) {
    override fun toString() = "SocialReauth(provider=$provider, authorizationCode=<redacted>, codeVerifier=<redacted>)"
}

/** 소셜 다시 인증을 확인하는 고리 — `auth-social` 이 있을 때 [dev.sumin.skeleton.account.social.AccountSocialAutoConfiguration] 이 내놓는다 */
fun interface SocialReauthVerifier {
    /** 코드가 [accountId] 에 이미 연결된 제공자 계정의 것이면 true (코드 교환 실패 · 다른 계정의 것이면 false) */
    fun verify(accountId: String, proof: SocialReauth): Boolean
}

/** 다시 인증에 쓰는 증거 — 계정이 가진 것에 맞는 하나만 의미가 있다 (비밀번호 · 메일 코드 · 이메일 없는 계정의 소셜 코드) */
data class ReauthInput(val currentPassword: String? = null, val confirmationCode: String? = null, val social: SocialReauth? = null) {
    override fun toString() = "ReauthInput(<redacted>)"
}

/**
 * 민감한 일(이메일 변경 · 첫 비밀번호 · 로그인 수단 연결 · 해제 · 삭제) 앞의 **다시 인증** — 15분짜리 액세스 토큰 하나가 영구 자격(바뀐 주소 · 새 비밀번호 · 붙은 소셜)으로 바뀌지 못하게 한다:
 *  - 비밀번호가 있는 계정: 현재 비밀번호
 *  - 비밀번호는 없고 주소가 있는 계정: 그 주소로 보낸 6자리 코드 ([requestConfirmation]) — 계정 · **세션**에 묶여 있어 다른 세션에서는 못 쓴다
 *  - 주소가 없는 계정(Naver · 확인 안 된 이메일로 가입한 소셜): **지금 새로 받은** 이미 연결된 제공자의 인가 코드 ([SocialReauth])
 */
class Reauth(private val core: AccountCore) {
    /** [check] 가 통과한 뒤 일이 끝까지 가면 [commit] — 정책 검사 같은 실패가 코드를 태우지 않게 둘로 나눈다 */
    class Proof internal constructor(private val onCommit: () -> Unit) {
        fun commit() = onCommit()

        companion object { val NONE = Proof {} }
    }

    /** 계정 주소로 6자리 코드를 보낸다 — 이 세션에서만 쓸 수 있다 (응답은 늘 같다 — 주소가 없으면 조용히, [CodeWindow] 도 같은 계산) */
    fun requestConfirmation(accountId: String, sessionId: String?): CodeWindow {
        val account = core.accounts.findById(accountId) ?: throw AccountException(AccountErrorCode.NOT_FOUND)
        val c = core.props.emailChange
        val a = core.limits.acquire("reauth-confirmation:account", accountId, c.perAccount, c.perAccountWindow)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        val window = core.codeWindow(c.ttl)
        val email = account.email ?: return window
        val opened = core.challenges.open(ChallengePurposes.REAUTH, accountId, c.ttl, core.props.verification.maxAttempts, accountId = accountId, sessionId = sessionId)
        core.tasks.run("reauth-confirmation") {
            core.mailer.send(AccountMail(MailKind.REAUTH_CODE, email, account.locale, vars = mapOf("code" to opened.code, "minutes" to c.ttl.toMinutes().toString())))
        }
        return window
    }

    /**
     * 통과하면 [Proof] — 일을 마친 뒤 `commit()`. 코드는 틀리면 [CodeInvalidException](남은 시도), 없음 · 만료 · 소진 · 다른 세션이면 CODE_EXPIRED, 아예 안 보냈으면 REAUTH_REQUIRED.
     * [codePurpose]: 메일 코드의 용도 (삭제는 따로). [passwordFailure]: 비밀번호가 틀렸을 때의 에러.
     */
    fun check(
        account: Account, input: ReauthInput, sessionId: String?,
        codePurpose: String = ChallengePurposes.REAUTH, passwordFailure: AccountErrorCode = AccountErrorCode.CURRENT_PASSWORD_INVALID,
    ): Proof {
        val email = account.email
        val hash = email?.let { core.accounts.findIdentity(SignInMethods.PASSWORD, it)?.secret }
        if (hash != null) {
            if (input.currentPassword == null || !core.hasher.matches(input.currentPassword, hash)) throw AccountException(passwordFailure)
            return Proof.NONE
        }
        if (email == null) return socialProof(account, input.social)
        val code = input.confirmationCode ?: throw AccountException(AccountErrorCode.REAUTH_REQUIRED)
        val row = when (val checked = core.challenges.checkOpen(codePurpose, account.id, code, sessionId)) {
            is CodeCheck.Ok -> checked.row
            is CodeCheck.Wrong -> throw if (checked.attemptsLeft <= 0) AccountException(AccountErrorCode.CODE_EXPIRED) else CodeInvalidException(checked.attemptsLeft)
            CodeCheck.Gone -> throw AccountException(AccountErrorCode.CODE_EXPIRED)
        }
        // 코드는 한 번만 — 일을 끝까지 하고 나서 태운다 (두 요청이 한 코드로 겹치면 하나만 이긴다)
        return Proof { if (!core.challenges.consume(row.id)) throw AccountException(AccountErrorCode.CODE_EXPIRED) }
    }

    /** 이메일이 없는 계정 — 이미 연결된 제공자의 **새** 인가 코드. 확인할 고리가 없거나(소셜 모듈 없음) 안 보냈으면 REAUTH_REQUIRED, 이 계정의 것이 아니면 REAUTH_FAILED */
    private fun socialProof(account: Account, social: SocialReauth?): Proof {
        social ?: throw AccountException(AccountErrorCode.REAUTH_REQUIRED)
        val verifier = core.socialReauth() ?: throw AccountException(AccountErrorCode.REAUTH_REQUIRED)
        val login = core.props.login
        val a = core.limits.acquire("reauth-social:account", account.id, login.perAccount, login.window)
        if (!a.allowed) throw RateLimitedException(a.retryAfterSeconds)
        if (!verifier.verify(account.id, social)) throw AccountException(AccountErrorCode.REAUTH_FAILED)
        return Proof.NONE
    }
}
