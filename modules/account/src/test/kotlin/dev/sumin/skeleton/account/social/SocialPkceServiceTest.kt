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
import dev.sumin.skeleton.auth.social.oauth.OAuthCodeExchange
import dev.sumin.skeleton.auth.social.oauth.OAuthPkceException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.auth.social.oauth.PkceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SocialPkceServiceTest {
    private class Idp(override val pkce: PkceMode) : OAuthProvider {
        override val providerId = "pk"
        val seen = mutableListOf<OAuthCodeExchange>()
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile = error("new path only")
        override fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile {
            seen += exchange
            return OAuthUserProfile("pk", "pk-user-${exchange.authorizationCode}", null, null, null)
        }
    }

    private val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val idp = Idp(PkceMode.REQUIRED)
    private val oauth = OAuthProviderRegistry(listOf(idp), AuthSocialProperties(mapOf("pk" to AuthSocialProperties.Provider(enabled = true))))
    private val h = AccountHarness()
    private val methods = SignInMethodRegistry(listOf(PasswordSignInMethod(), SocialSignInMethod("pk")))
    private val links = SocialLinkService(oauth, IdentityService(h.core, methods), h.core)

    @Test
    fun `a link without the verifier is refused BEFORE the re-authentication is spent and the provider is not called`() {
        val a = h.activeAccount()
        assertFailsWith<OAuthPkceException> { links.link(a.id, "pk", "good", null, ReauthInput("tangerine-42-moon"), "ses_1") }
        assertEquals(0, idp.seen.size)
        // the very same proof still works with the verifier - it was not burned
        links.link(a.id, "pk", "good", null, ReauthInput("tangerine-42-moon"), "ses_1", codeVerifier = verifier)
        assertEquals(verifier, idp.seen.single().codeVerifier)
    }

    @Test
    fun `the verifier of a social re-authentication is forwarded and a missing one raises the PKCE error instead of answering false`() {
        val now = h.time.now()
        h.repo.insert(Account("acc_s", null, false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        IdentityService(h.core, methods).link("acc_s", "pk", "pk-user-good", verified = true)
        val verifierService = AccountSocialReauthVerifier(oauth, h.core)
        assertFailsWith<OAuthPkceException> { verifierService.verify("acc_s", SocialReauth("pk", "good")) }
        assertTrue(verifierService.verify("acc_s", SocialReauth("pk", "good", codeVerifier = verifier)))
        assertEquals(verifier, idp.seen.last().codeVerifier)
    }
}
