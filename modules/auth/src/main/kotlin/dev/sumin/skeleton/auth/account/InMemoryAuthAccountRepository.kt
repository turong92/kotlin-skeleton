package dev.sumin.skeleton.auth.account

class InMemoryAuthAccountRepository(
    private val accounts: List<AuthAccount>,
) : AuthAccountRepository {
    override fun findBy(identifier: AccountIdentifier): AuthAccount? =
        identifier.accountId?.let { accountId ->
            accounts.firstOrNull { it.accountId == accountId }
        } ?: identifier.email?.let { email ->
            accounts.firstOrNull { it.email == email }
        } ?: identifier.username?.let { username ->
            accounts.firstOrNull { it.username == username }
        }
}
