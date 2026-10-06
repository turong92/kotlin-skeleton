package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.SocialReauth
import dev.sumin.skeleton.account.SocialReauthVerifier
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderGatewayException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.codeExchange

/**
 * 이메일이 없는 계정의 다시 인증 — 제공자에 **새** 인가 코드를 교환해 나온 제공자 계정이 **이 계정에 이미 연결된** 것(`findIdentity(provider, sub).accountId == accountId`)이면 통과.
 * 다른 계정의 제공자 계정 · 연결되지 않은 제공자 · 제공자가 거부한 코드는 false. 제공자 쪽 장애는 그대로 던진다 (일시적 실패를 "틀림" 으로 속이지 않는다).
 */
class AccountSocialReauthVerifier(private val registry: OAuthProviderRegistry, private val core: AccountCore) : SocialReauthVerifier {
    override fun verify(accountId: String, proof: SocialReauth): Boolean {
        val provider = registry.findEnabled(proof.provider) ?: return false
        // 검증기가 없거나 틀린 모양이면 "증명이 틀렸다" 가 아니라 400 AUTH.SOCIAL_PKCE_FAILED — 프론트의 버그를 가리지 않는다
        val exchange = provider.codeExchange(proof.authorizationCode, proof.redirectUri, proof.codeVerifier, proof.nonce)
        val profile = try {
            provider.fetchProfile(exchange)
        } catch (_: OAuthInvalidAuthorizationCodeException) {
            return false
        } catch (ex: RuntimeException) {
            throw OAuthProviderGatewayException(provider.providerId, ex)
        }
        return core.accounts.findIdentity(provider.providerId.trim().lowercase(), profile.providerUserId)?.accountId == accountId
    }
}
