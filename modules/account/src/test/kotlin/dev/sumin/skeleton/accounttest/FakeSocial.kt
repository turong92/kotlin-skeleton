package dev.sumin.skeleton.accounttest

import dev.sumin.skeleton.auth.social.oauth.NativeTokenKind
import dev.sumin.skeleton.auth.social.oauth.OAuthIdTokenInvalidException
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthNativeToken
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/** 코드 → 프로필 표로 답하는 가짜 제공자 `fakeidp` — 네트워크 없이 소셜 흐름을 돌린다 */
class FakeOAuthProvider(override val providerId: String = "fakeidp") : OAuthProvider {
    /** `fakeidp` 만 앱 토큰 입구가 있다 (`fakeidp2` 는 없다) – `t-<x>` 토큰은 `c-<x>` 코드와 같은 사용자(같은 sub)다 */
    override val nativeToken: NativeTokenKind? = if (providerId == "fakeidp") NativeTokenKind.ID_TOKEN else null

    override fun verifyNativeToken(token: OAuthNativeToken): OAuthUserProfile =
        if (token.token.startsWith("t-")) fetchProfile("c-" + token.token.removePrefix("t-"), null) else throw OAuthIdTokenInvalidException(providerId, "test token is not t-<x>")

    override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile = when (authorizationCode) {
        "c-new" -> OAuthUserProfile(providerId, "sub-new", "social-new@example.com", "social-new@example.com", "Social New", emailVerified = true)
        "c-unverified" -> OAuthUserProfile(providerId, "sub-unverified", "victim@example.com", "victim@example.com", "Mallory", emailVerified = false)
        "c-conflict" -> OAuthUserProfile(providerId, "sub-conflict", "taken@example.com", "taken@example.com", "Conflict", emailVerified = true)
        "c-other" -> OAuthUserProfile(providerId, "sub-other", "other@example.com", "other@example.com", "Other", emailVerified = true)
        "c-link" -> OAuthUserProfile(providerId, "sub-link", "link@example.com", "link@example.com", "Link", emailVerified = true)
        "c-noemail-del" -> OAuthUserProfile(providerId, "sub-noemail-del", null, null, "No Mail Del", emailVerified = false)
        "c-noemail-chg" -> OAuthUserProfile(providerId, "sub-noemail-chg", null, null, "No Mail Chg", emailVerified = false)
        "c-noemail-req" -> OAuthUserProfile(providerId, "sub-noemail-req", null, null, "No Mail Req", emailVerified = false)
        "c-noemail-req2" -> OAuthUserProfile(providerId, "sub-noemail-req2", null, null, "No Mail Req 2", emailVerified = false)
        "c-noemail-req3" -> OAuthUserProfile(providerId, "sub-noemail-req3", null, null, "No Mail Req 3", emailVerified = false)
        "c-third" -> OAuthUserProfile(providerId, "sub-third", "third@example.com", "third@example.com", "Third", emailVerified = true)
        else -> if (authorizationCode.startsWith("c-noemail-")) OAuthUserProfile(providerId, "sub-$authorizationCode", null, null, "No Mail", emailVerified = false) else throw OAuthInvalidAuthorizationCodeException(providerId)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeSocialBeans {
    @Bean fun fakeOAuthProvider(): OAuthProvider = FakeOAuthProvider()

    /** 두 번째 제공자 — "이미 가진 제공자" 와 "새 제공자" 를 가르는 시험용 (`skeleton.auth-social.providers.fakeidp2.enabled=true` 로 켠 시험에서만 쓰인다) */
    @Bean fun fakeOAuthProvider2(): OAuthProvider = FakeOAuthProvider("fakeidp2")
}
