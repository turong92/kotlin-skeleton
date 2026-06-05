package dev.sumin.skeleton.auth.social.oauth

data class OAuthAccountLink(
    val provider: String,
    val providerUserId: String,
    val accountId: String,
)
