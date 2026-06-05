package dev.sumin.skeleton.auth.social.oauth

interface OAuthAccountLinkRepository {
    fun findAccountId(provider: String, providerUserId: String): String?
}
