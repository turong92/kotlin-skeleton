package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 제공자 프로필의 `emailAuthoritative` 가 로그인 증명까지 그대로 전달된다 (Google: 확인했다 ≠ 권위자) */
class ProvisioningPolicyAuthorityTest {
    private val social = AccountProperties.Social(signUp = true, mergeOnVerifiedEmail = true)
    private fun harness() = AccountHarness(AccountProperties(social = social, mail = AccountProperties.Mail(linkBaseUrl = "https://x"), password = AccountProperties.Password(bcryptStrength = 4)))
    private fun policy(h: AccountHarness) = AccountOAuthProvisioningPolicy(AccountSignInService(h.core, SignInMethodRegistry(listOf(PasswordSignInMethod(), SocialSignInMethod("google")))), social)
    private fun profile(authoritative: Boolean) = OAuthUserProfile("google", "g-1", "ann@example.com", null, "Ann", emailVerified = true, emailAuthoritative = authoritative)

    @Test
    fun `an authoritative profile merges into the account with that address`() {
        val h = harness(); val owner = h.activeAccount("ann@example.com")
        assertEquals(owner.id, policy(h).resolveOrCreateAccount(profile(authoritative = true)))
    }

    @Test
    fun `a verified but non-authoritative profile is the 409 - never a merge`() {
        val h = harness(); h.activeAccount("ann@example.com")
        val ex = assertFailsWith<ApplicationException> { policy(h).resolveOrCreateAccount(profile(authoritative = false)) }
        assertEquals("ACCOUNT.SOCIAL_EMAIL_CONFLICT", ex.errorCode.code)
    }
}
