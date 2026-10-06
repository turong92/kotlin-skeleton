package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountCore
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.SignInMethodSource
import dev.sumin.skeleton.account.web.AccountCallers
import dev.sumin.skeleton.account.web.AccountWebAutoConfiguration
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * `auth-social` 이 클래스패스에 있을 때만: 제공자 연결 표를 계정의 로그인 수단 표로 바꾸고, 제공자마다 로그인 수단을 등록하고, 연결 · 가입 정책을 건다.
 * `auth-social` 의 기본(가짜 메모리 연결 표 · 연결된 계정만 허용)보다 먼저 평가해 그쪽이 물러나게 한다.
 */
@AutoConfiguration(
    after = [AccountAutoConfiguration::class],
    beforeName = ["dev.sumin.skeleton.auth.social.config.AuthSocialAutoConfiguration"],
)
@ConditionalOnClass(name = ["dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry"])
class AccountSocialAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(OAuthAccountLinkRepository::class)
    fun accountOAuthLinkRepository(core: AccountCore): OAuthAccountLinkRepository = AccountOAuthLinkRepository(core)

    @Bean
    @ConditionalOnMissingBean(OAuthAccountProvisioningPolicy::class)
    fun accountOAuthProvisioningPolicy(signIn: AccountSignInService, properties: AccountProperties): OAuthAccountProvisioningPolicy =
        AccountOAuthProvisioningPolicy(signIn, properties.social)

    /** 앱에 있는 `OAuthProvider` 마다 로그인 수단 하나 — 새 제공자 모듈을 더하면 이 코드는 그대로다 */
    @Bean
    @ConditionalOnMissingBean(name = ["socialSignInMethodSource"])
    fun socialSignInMethodSource(providers: ObjectProvider<OAuthProvider>): SignInMethodSource =
        SignInMethodSource { providers.orderedStream().toList().map { SocialSignInMethod(it.providerId.trim().lowercase()) } }
}

@AutoConfiguration(after = [AccountSocialAutoConfiguration::class, AccountWebAutoConfiguration::class])
@ConditionalOnClass(name = ["dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "skeleton.account.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class AccountSocialWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun socialLinkService(registry: OAuthProviderRegistry, identities: IdentityService, core: AccountCore): SocialLinkService = SocialLinkService(registry, identities, core)

    @Bean
    @ConditionalOnMissingBean
    fun socialIdentityController(callers: AccountCallers, links: SocialLinkService): SocialIdentityController = SocialIdentityController(callers, links)
}
