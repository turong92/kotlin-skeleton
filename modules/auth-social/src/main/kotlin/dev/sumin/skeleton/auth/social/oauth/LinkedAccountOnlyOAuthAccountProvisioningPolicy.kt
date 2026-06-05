package dev.sumin.skeleton.auth.social.oauth

class LinkedAccountOnlyOAuthAccountProvisioningPolicy(
    private val linkRepository: OAuthAccountLinkRepository,
) : OAuthAccountProvisioningPolicy {
    override fun resolveOrCreateAccount(profile: OAuthUserProfile): String? =
        linkRepository.findAccountId(profile.provider, profile.providerUserId)
}
