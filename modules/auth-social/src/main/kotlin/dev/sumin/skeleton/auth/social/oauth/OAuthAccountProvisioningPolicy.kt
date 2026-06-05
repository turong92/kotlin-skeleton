package dev.sumin.skeleton.auth.social.oauth

interface OAuthAccountProvisioningPolicy {
    fun resolveOrCreateAccount(profile: OAuthUserProfile): String?
}
