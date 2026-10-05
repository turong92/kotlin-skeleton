package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.IdentityView
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInProof
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderGatewayException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderNotFoundException
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile

/** 소셜 제공자 하나 = 로그인 수단 하나. 코드는 제공자 id(`google` · `kakao` …), 주체는 제공자의 사용자 id (화면에 내보내지 않는다) */
class SocialSignInMethod(override val code: String) : SignInMethod

/** auth-social 의 "제공자 계정 → 내부 계정" 포트를 계정의 로그인 수단 표로 구현한다 (메모리 가짜 연결 표를 대신한다) */
class AccountOAuthLinkRepository(private val core: AccountCore) : OAuthAccountLinkRepository {
    override fun findAccountId(provider: String, providerUserId: String): String? =
        core.accounts.findIdentity(provider.trim().lowercase(), providerUserId)?.accountId
}

/**
 * 소셜 로그인의 계정 결정: 이미 연결됐으면 그 계정, 아니면 `skeleton.account.social.sign-up` 이 켜져 있을 때만 새 계정.
 * 제공자가 확인한 이메일이 기존 계정과 겹치면 **병합하지 않고** ACCOUNT.SOCIAL_EMAIL_CONFLICT ([AccountSignInService] 의 규칙).
 */
class AccountOAuthProvisioningPolicy(private val signIn: AccountSignInService, private val props: AccountProperties.Social) : OAuthAccountProvisioningPolicy {
    override fun resolveOrCreateAccount(profile: OAuthUserProfile): String? =
        signIn.signIn(
            SignInProof(
                method = profile.provider.trim().lowercase(), subject = profile.providerUserId, email = profile.email, emailVerified = profile.emailVerified,
                displayName = profile.displayName, allowSignUp = props.signUp,
            ),
        )?.accountId
}

/** 로그인한 계정이 제공자 계정을 자기 로그인 수단으로 붙인다 — 코드 교환은 로그인과 같은 제공자 클라이언트를 쓴다 */
class SocialLinkService(private val registry: OAuthProviderRegistry, private val identities: IdentityService) {
    fun link(accountId: String, providerId: String, authorizationCode: String, redirectUri: String?): IdentityView {
        val provider = registry.findEnabled(providerId) ?: throw OAuthProviderNotFoundException(providerId)
        val profile = try {
            provider.fetchProfile(authorizationCode, redirectUri)
        } catch (ex: OAuthInvalidAuthorizationCodeException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw OAuthProviderGatewayException(provider.providerId, ex)
        }
        return identities.link(accountId, provider.providerId.trim().lowercase(), profile.providerUserId, verified = true)
    }
}
