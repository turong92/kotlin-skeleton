package dev.sumin.skeleton.auth.social.oauth

data class OAuthUserProfile(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val username: String?,
    val displayName: String?,
)
