package dev.sumin.skeleton.account.social

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountHarness
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.ReauthInput
import dev.sumin.skeleton.account.SocialReauth
import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.signin.IdentityService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.ApplicationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SocialLinkServiceTest {
    private class Provider(override val providerId: String) : OAuthProvider {
        val exchanges = java.util.concurrent.atomic.AtomicInteger()
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
            if (exchanges.incrementAndGet() < 0) throw IllegalStateException() else if (authorizationCode.startsWith("good")) OAuthUserProfile(providerId, "$providerId-user-$authorizationCode", null, null, null) else throw OAuthInvalidAuthorizationCodeException(providerId)
    }

    private val providers = listOf(Provider("alpha"), Provider("beta"))
    private val props = AuthSocialProperties(providers = providers.associate { it.providerId to AuthSocialProperties.Provider(enabled = true) })
    private val oauth = OAuthProviderRegistry(providers, props)
    private val h = AccountHarness()
    private val methods = SignInMethodRegistry(listOf(PasswordSignInMethod(), SocialSignInMethod("alpha"), SocialSignInMethod("beta")))
    private val links = SocialLinkService(oauth, IdentityService(h.core, methods), h.core)
    private val ses = "ses_1"

    private fun passwordless(): Account {
        val now = h.time.now()
        return Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now).also { h.repo.insert(it, emptyList()) }
    }

    @Test
    fun `one mailed code authorizes one link - two concurrent links with it cannot both succeed`() {
        repeat(20) {
            val h2 = AccountHarness()
            val now = h2.time.now()
            h2.repo.insert(Account("acc_s", "s@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
            val service = SocialLinkService(oauth, IdentityService(h2.core, methods), h2.core)
            h2.reauth.requestConfirmation("acc_s", ses)
            val code = h2.mailer.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
            val pool = Executors.newFixedThreadPool(2)
            val go = CountDownLatch(1)
            val results = listOf("alpha", "beta").map { p -> pool.submit<Result<Any>> { go.await(); runCatching { service.link("acc_s", p, "good-1", null, ReauthInput(confirmationCode = code), ses) } } }
            go.countDown()
            val outcomes = results.map { it.get() }
            pool.shutdown()
            assertEquals(1, outcomes.count { it.isSuccess }, "the proof is committed before linking, so a single code cannot be spent twice")
            assertEquals(1, h2.repo.identitiesOf("acc_s").size)
        }
    }

    @Test
    fun `a social re-authentication proves only a provider that is already linked to this very account`() {
        passwordless()
        val ids = IdentityService(h.core, methods)
        ids.link("acc_s", "alpha", "alpha-user-good-own", verified = true)
        val verifier = AccountSocialReauthVerifier(oauth, h.core)
        assertTrue(verifier.verify("acc_s", SocialReauth("alpha", "good-own")), "a fresh code of the linked provider account")
        assertEquals(false, verifier.verify("acc_s", SocialReauth("alpha", "good-someone-else")), "another provider account of the same provider")
        assertEquals(false, verifier.verify("acc_s", SocialReauth("beta", "good-own")), "a provider that is not linked")
        assertEquals(false, verifier.verify("acc_s", SocialReauth("alpha", "bad")), "a code the provider refuses")
        assertEquals(false, verifier.verify("acc_s", SocialReauth("nope", "good-own")), "an unknown provider")
        assertEquals(false, verifier.verify("acc_other", SocialReauth("alpha", "good-own")), "the identity belongs to another account")
    }

    @Test
    fun `linking without any proof for an account that has a password is refused`() {
        val a = h.activeAccount()
        val e = assertFailsWith<ApplicationException> { links.link(a.id, "alpha", "good-1", null, ReauthInput(), ses) }
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", e.errorCode.code)
    }

    @Test
    fun `the re-authentication proof is checked BEFORE the authorization code is exchanged - a code is single use at the provider, so a wrong password must not burn it`() {
        val a = h.activeAccount()
        val alpha = providers[0] as Provider
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", assertFailsWith<ApplicationException> { links.link(a.id, "alpha", "good-1", null, ReauthInput("wrong-password-1"), ses) }.errorCode.code)
        assertEquals(0, alpha.exchanges.get(), "the provider was not called")
        links.link(a.id, "alpha", "good-1", null, ReauthInput("tangerine-42-moon"), ses)   // the retry with the same code and the right password
        assertEquals(1, alpha.exchanges.get())

        passwordless()
        assertEquals("ACCOUNT.REAUTH_REQUIRED", assertFailsWith<ApplicationException> { links.link("acc_s", "beta", "good-2", null, ReauthInput(), ses) }.errorCode.code)
        assertEquals("ACCOUNT.CODE_EXPIRED", assertFailsWith<ApplicationException> { links.link("acc_s", "beta", "good-2", null, ReauthInput(confirmationCode = "000000"), ses) }.errorCode.code, "no code was requested")
        assertEquals(0, (providers[1] as Provider).exchanges.get(), "no exchange without a passing proof")
    }
}
