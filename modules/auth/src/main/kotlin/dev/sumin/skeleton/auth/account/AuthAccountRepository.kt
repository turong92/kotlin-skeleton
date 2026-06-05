package dev.sumin.skeleton.auth.account

interface AuthAccountRepository {
    fun findBy(identifier: AccountIdentifier): AuthAccount?
}
