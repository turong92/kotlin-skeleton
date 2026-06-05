package dev.sumin.skeleton.auth.social.oauth

class InMemoryOAuthAccountLinkRepository(
    links: List<OAuthAccountLink>,
) : OAuthAccountLinkRepository {
    private val linksByProviderAndUserId = links.associateBy {
        Key(provider = it.provider.normalizeProvider(), providerUserId = it.providerUserId)
    }

    override fun findAccountId(provider: String, providerUserId: String): String? =
        linksByProviderAndUserId[Key(provider.normalizeProvider(), providerUserId)]?.accountId

    private fun String.normalizeProvider(): String = trim().lowercase()

    private data class Key(
        val provider: String,
        val providerUserId: String,
    )
}
