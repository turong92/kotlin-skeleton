package dev.sumin.skeleton.auth.account

import dev.sumin.skeleton.auth.account.AuthAccount

class SeedAuthAccountRepository(accounts: List<AuthAccount>) : AuthAccountRepository {
    private val delegate = InMemoryAuthAccountRepository(accounts)
    override fun findBy(identifier: AccountIdentifier): AuthAccount? = delegate.findBy(identifier)
}
