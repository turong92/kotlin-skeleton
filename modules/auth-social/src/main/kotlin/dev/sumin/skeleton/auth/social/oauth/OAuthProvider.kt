package dev.sumin.skeleton.auth.social.oauth

interface OAuthProvider {
    val providerId: String

    fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile
}
