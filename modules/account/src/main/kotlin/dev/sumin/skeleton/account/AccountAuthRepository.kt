package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.token.TokenPurposes
import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.LoginBlock

/**
 * 진짜 계정 저장소를 `auth` 의 [AuthAccountRepository] 포트로 보인다 — `auth` 는 이 모듈이 있다는 것을 모른 채 실제 계정으로 로그인한다.
 * `username` 은 이메일이다 (이 모듈이 만든 계정에는 따로 사용자 이름이 없다). 삭제된 계정은 없는 것처럼 null.
 */
class AccountAuthRepository(private val core: AccountCore) : AuthAccountRepository {
    override fun findBy(identifier: AccountIdentifier): AuthAccount? {
        val id = identifier.accountId
        val email = identifier.email
        val username = identifier.username
        val account = when {
            id != null -> core.accounts.findById(id)
            email != null -> core.accountByEmail(Emails.normalize(email))
            username != null -> core.accountByEmail(Emails.normalize(username))
            else -> null
        } ?: return null
        return toAuth(account)
    }

    /** [via]: 로그인에 성공한 수단 코드 — 탈퇴 취소 토큰에 실려 취소 뒤 로그인 기록이 실제 수단을 적게 한다 (모르면 비밀번호 로그인 길이다) */
    fun toAuth(account: Account, via: String = SignInMethods.PASSWORD): AuthAccount? {
        val pending = pendingDeletion(account)
        if (account.status.departed && !pending) return null
        val hash = account.email?.let { core.accounts.findIdentity(SignInMethods.PASSWORD, it)?.secret }.orEmpty()
        return AuthAccount(
            accountId = account.id,
            username = account.email ?: account.id,
            email = account.email.orEmpty(),
            passwordHash = hash,
            roles = account.roles,
            loginBlock = when (account.status) {
                AccountStatus.PENDING_VERIFICATION -> LoginBlock.EMAIL_NOT_VERIFIED
                AccountStatus.SUSPENDED -> LoginBlock.SUSPENDED
                else -> if (pending) LoginBlock.DELETION_PENDING else null
            },
            blockData = if (pending) ({ restoreState(account, via) }) else null,
        )
    }

    /** 탈퇴 유예가 아직 안 끝난 DELETED 계정이고 `deletion.self-restore` 가 켜져 있나 */
    private fun pendingDeletion(account: Account) =
        core.props.deletion.selfRestore && account.status == AccountStatus.DELETED && account.purgeAfter?.isAfter(core.time.now()) == true

    /** 로그인에 성공해 토큰을 내려던 순간에만 부른다 — 새 취소 토큰이 이전 것을 닫는다 */
    private fun restoreState(account: Account, via: String): Map<String, Any?> {
        val raw = core.tokens.issue(TokenPurposes.DELETION_RESTORE, account.id, account.id, core.props.deletion.selfRestoreTtl, payload = via)
        return mapOf("purgeAfter" to account.purgeAfter, "restoreToken" to raw, "restoreTokenExpiresAt" to core.time.now().plus(core.props.deletion.selfRestoreTtl))
    }

    override val storesUpgradedPasswordHash: Boolean = true

    override fun upgradePasswordHash(accountId: String, newHash: String, oldHash: String) {
        val email = core.accounts.findById(accountId)?.email ?: return
        core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.let { core.accounts.updateIdentitySecret(it.id, newHash, expectedSecret = oldHash) }
    }

    override fun upgradePasswordHash(accountId: String, newHash: String) {
        val email = core.accounts.findById(accountId)?.email ?: return
        core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.let { core.accounts.updateIdentitySecret(it.id, newHash) }
    }
}
