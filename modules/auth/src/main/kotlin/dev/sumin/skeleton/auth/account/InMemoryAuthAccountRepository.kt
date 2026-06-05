package dev.sumin.skeleton.auth.account

class InMemoryAuthAccountRepository(
    private val accounts: List<AuthAccount>,
) : AuthAccountRepository {
    override fun findBy(identifier: AccountIdentifier): AuthAccount? =
        when {
            identifier.accountId != null -> accounts.firstOrNull { it.accountId == identifier.accountId }
            identifier.email != null -> accounts.firstOrNull { it.email == identifier.email }
            identifier.username != null -> accounts.firstOrNull { it.username == identifier.username }
            else -> null
        }
}
