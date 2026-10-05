package dev.sumin.skeleton.accounttest

import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** 코드 → 프로필 표로 답하는 가짜 제공자 `fakeidp` — 네트워크 없이 소셜 흐름을 돌린다 */
class FakeOAuthProvider : OAuthProvider {
    override val providerId = "fakeidp"
    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile = when (authorizationCode) {
        "c-new" -> OAuthUserProfile("fakeidp", "sub-new", "social-new@example.com", "social-new@example.com", "Social New", emailVerified = true)
        "c-unverified" -> OAuthUserProfile("fakeidp", "sub-unverified", "victim@example.com", "victim@example.com", "Mallory", emailVerified = false)
        "c-conflict" -> OAuthUserProfile("fakeidp", "sub-conflict", "taken@example.com", "taken@example.com", "Conflict", emailVerified = true)
        "c-other" -> OAuthUserProfile("fakeidp", "sub-other", "other@example.com", "other@example.com", "Other", emailVerified = true)
        "c-link" -> OAuthUserProfile("fakeidp", "sub-link", "link@example.com", "link@example.com", "Link", emailVerified = true)
        else -> throw OAuthInvalidAuthorizationCodeException("fakeidp")
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeSocialBeans {
    @Bean fun fakeOAuthProvider(): OAuthProvider = FakeOAuthProvider()
}
