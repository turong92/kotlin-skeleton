package dev.sumin.skeleton.account

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

    fun toAuth(account: Account): AuthAccount? {
        if (account.status == AccountStatus.DELETED) return null
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
                else -> null
            },
        )
    }

    override val storesUpgradedPasswordHash: Boolean = true

    override fun upgradePasswordHash(accountId: String, newHash: String) {
        val email = core.accounts.findById(accountId)?.email ?: return
        core.accounts.findIdentity(SignInMethods.PASSWORD, email)?.let { core.accounts.updateIdentitySecret(it.id, newHash) }
    }
}
