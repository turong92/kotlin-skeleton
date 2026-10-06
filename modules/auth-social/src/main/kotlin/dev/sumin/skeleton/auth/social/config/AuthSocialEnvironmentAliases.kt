package dev.sumin.skeleton.auth.social.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/** `auth-social` 의 제공자(google · kakao · naver)에 규칙 [ProviderEnvironmentAliases] 를 적용한다: `SKELETON_AUTH_SOCIAL_PROVIDERS_<제공자>_<키>` → `skeleton.auth-social.providers.<제공자>.<키>` */
object AuthSocialEnvironmentAliases {
    fun fromEnvironment(env: Map<String, String>): Map<String, Any> =
        ProviderEnvironmentAliases.fromEnvironment(env, "SKELETON_AUTH_SOCIAL_PROVIDERS_", "skeleton.auth-social.providers")
}

class AuthSocialEnvironmentAliasPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) =
        ProviderEnvironmentAliases.apply(environment, "SKELETON_AUTH_SOCIAL_PROVIDERS_", "skeleton.auth-social.providers", "authSocialEnvironmentAliases")
}
