package dev.sumin.skeleton.auth.social.oidc

import dev.sumin.skeleton.auth.social.config.ProviderEnvironmentAliases
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/** `auth-social-oidc` 의 제공자에 규칙 [ProviderEnvironmentAliases] 를 적용한다: `SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_<코드>_<키>` → `skeleton.auth-social-oidc.providers.<코드>.<키>` (`CLAIMS_` 만 중첩) */
object OidcEnvironmentAliases {
    fun fromEnvironment(env: Map<String, String>): Map<String, Any> =
        ProviderEnvironmentAliases.fromEnvironment(env, "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_", "skeleton.auth-social-oidc.providers", setOf("claims"))
}

class OidcEnvironmentAliasPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) =
        ProviderEnvironmentAliases.apply(environment, "SKELETON_AUTH_SOCIAL_OIDC_PROVIDERS_", "skeleton.auth-social-oidc.providers", "authSocialOidcEnvironmentAliases", setOf("claims"))
}
