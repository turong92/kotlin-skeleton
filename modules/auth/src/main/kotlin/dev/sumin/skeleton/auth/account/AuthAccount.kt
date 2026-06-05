package dev.sumin.skeleton.auth.account

data class AuthAccount(
    val accountId: String,
    val username: String,
    val email: String,
    val passwordHash: String,
    val roles: Set<String>,
)
