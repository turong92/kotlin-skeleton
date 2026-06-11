package dev.sumin.skeleton.auth.social.config

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.social.api.OAuthSocialAuthController
import dev.sumin.skeleton.auth.social.api.OAuthSocialLoginRequest
import dev.sumin.skeleton.auth.social.oauth.InMemoryOAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.LinkedAccountOnlyOAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLink
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService
import dev.sumin.skeleton.common.Response
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.RequestPredicates.POST
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions.route
import org.springframework.web.servlet.function.ServerResponse

@AutoConfiguration
@EnableConfigurationProperties(AuthSocialProperties::class)
class AuthSocialAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun oauthProviderRegistry(
        providers: List<OAuthProvider>,
        properties: AuthSocialProperties,
    ): OAuthProviderRegistry =
        OAuthProviderRegistry(providers, properties)

    @Bean
    @ConditionalOnMissingBean
    fun oauthAccountLinkRepository(): OAuthAccountLinkRepository =
        InMemoryOAuthAccountLinkRepository(
            links = listOf(
                OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user"),
                OAuthAccountLink(provider = "fake", providerUserId = "fake_admin", accountId = "acc_admin"),
            ),
        )

    @Bean
    @ConditionalOnMissingBean
    fun oauthAccountProvisioningPolicy(
        linkRepository: OAuthAccountLinkRepository,
    ): OAuthAccountProvisioningPolicy =
        LinkedAccountOnlyOAuthAccountProvisioningPolicy(linkRepository)

    @Bean
    @ConditionalOnMissingBean
    fun oauthSocialLoginService(
        providerRegistry: OAuthProviderRegistry,
        provisioningPolicy: OAuthAccountProvisioningPolicy,
        accountRepository: AuthAccountRepository,
        tokenResponseFactory: AuthTokenResponseFactory,
    ): OAuthSocialLoginService =
        OAuthSocialLoginService(
            providerRegistry = providerRegistry,
            provisioningPolicy = provisioningPolicy,
            accountRepository = accountRepository,
            tokenResponseFactory = tokenResponseFactory,
        )

    @Bean
    @ConditionalOnMissingBean
    fun oauthSocialAuthController(
        loginService: OAuthSocialLoginService,
    ): OAuthSocialAuthController =
        OAuthSocialAuthController(loginService)

    @Bean
    @ConditionalOnMissingBean(name = ["oauthSocialAuthRoutes"])
    fun oauthSocialAuthRoutes(
        controller: OAuthSocialAuthController,
    ): RouterFunction<ServerResponse> =
        route(POST("/api/v1/auth/social/{provider}/login")) { request ->
            val loginRequest = request.body(OAuthSocialLoginRequest::class.java)
            val response = controller.login(
                provider = request.pathVariable("provider"),
                request = loginRequest,
            )
            ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Response.ok(response))
        }
}
