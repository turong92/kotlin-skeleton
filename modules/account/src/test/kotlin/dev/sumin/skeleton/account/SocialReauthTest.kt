package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.MailKind
import dev.sumin.skeleton.account.signin.AccountSignInService
import dev.sumin.skeleton.account.signin.PasswordSignInMethod
import dev.sumin.skeleton.account.signin.SignInMethod
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.account.social.AccountSocialReauthVerifier
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthInvalidAuthorizationCodeException
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import dev.sumin.skeleton.common.ApplicationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 이메일이 없는 계정(Naver · 확인 안 된 이메일의 소셜 가입)도 민감한 일 앞에서 다시 인증한다 — 이미 연결된 제공자의 **새** 인가 코드로 */
class SocialReauthTest {
    private object Google : SignInMethod { override val code = "google" }
    private object Naver : SignInMethod { override val code = "naver" }

    /** 실제 제공자처럼 인가 코드는 **한 번만** 통한다. `good-own` 은 이 계정에 연결된 제공자 계정(naver-user-1)의 것, 다른 `good-…` 은 연결되지 않은 제공자 계정의 것 */
    private class OneShotProvider(override val providerId: String) : OAuthProvider {
        private val spent = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
            if (!authorizationCode.startsWith("good") || !spent.add(authorizationCode)) throw OAuthInvalidAuthorizationCodeException(providerId)
            return OAuthUserProfile(providerId, if (authorizationCode == "good-own") "naver-user-1" else "naver-someone-else", null, null, null)
        }
    }

    private lateinit var verifier: SocialReauthVerifier
    private val h = AccountHarness(socialReauth = SocialReauthVerifier { accountId, proof -> verifier.verify(accountId, proof) })
    init {
        val naver = OneShotProvider("naver")
        verifier = AccountSocialReauthVerifier(OAuthProviderRegistry(listOf(naver), AuthSocialProperties(providers = mapOf("naver" to AuthSocialProperties.Provider(enabled = true)))), h.core)
    }
    private val ses = "ses_1"
    private val good = ReauthInput(social = SocialReauth("naver", "good-own", "https://app/cb"))
    private val registry = SignInMethodRegistry(listOf(PasswordSignInMethod(), Google, Naver))
    private fun code(block: () -> Unit) = assertFailsWith<ApplicationException> { block() }.errorCode.code

    private fun emailless(): Account {
        val now = h.time.now()
        val account = Account("acc_n", null, false, AccountStatus.ACTIVE, setOf("USER"), "N", null, null, now, now)
        h.repo.insert(account, listOf(Identity("idn_n", "acc_n", "naver", "naver-user-1", true, createdAt = now), Identity("idn_g", "acc_n", "google", "g-user-1", true, createdAt = now)))
        return account
    }

    @Test
    fun `changing the email of an account that has none needs a fresh code of a linked provider`() {
        emailless()
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.emailChange.request("acc_n", "new@example.com", ReauthInput(), ses) })
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.emailChange.request("acc_n", "new@example.com", ReauthInput(social = SocialReauth("naver", "bad-or-stolen")), ses) })
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
        h.emailChange.request("acc_n", "new@example.com", good, ses)
        assertEquals("new@example.com", h.mailer.of(MailKind.EMAIL_CHANGE_CODE).single().to)
    }

    @Test
    fun `a code that proves a provider account of someone else does not count - the REAL ownership check runs`() {
        emailless()
        val now = h.time.now()   // the provider account "naver-someone-else" really exists - it is linked to ANOTHER account
        h.repo.insert(Account("acc_other", null, false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), listOf(Identity("idn_o", "acc_other", "naver", "naver-someone-else", true, createdAt = now)))
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.emailChange.request("acc_n", "new@example.com", ReauthInput(social = SocialReauth("naver", "good-someone-else")), ses) })
        assertEquals(0, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
    }

    @Test
    fun `a provider authorization code is single use - replaying the very code that just proved the account is rejected`() {
        emailless()
        h.emailChange.request("acc_n", "new@example.com", good, ses)
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.emailChange.request("acc_n", "other@example.com", good, ses) }, "the same code a second time")
        assertEquals(1, h.mailer.of(MailKind.EMAIL_CHANGE_CODE).size)
    }

    @Test
    fun `without a verifier (no social module) an account without an address cannot re-authenticate`() {
        val bare = AccountHarness()
        val now = bare.time.now()
        bare.repo.insert(Account("acc_n", null, false, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), emptyList())
        assertEquals("ACCOUNT.REAUTH_REQUIRED", assertFailsWith<ApplicationException> { bare.emailChange.request("acc_n", "new@example.com", good, ses) }.errorCode.code)
    }

    @Test
    fun `an account without an address can delete itself with the same proof`() {
        emailless()
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { h.deletion.delete("acc_n", ReauthInput(), ses) })
        assertEquals("ACCOUNT.REAUTH_FAILED", code { h.deletion.delete("acc_n", ReauthInput(social = SocialReauth("naver", "bad-code")), ses) })
        val purgeAfter = h.deletion.delete("acc_n", good, ses)
        assertEquals(AccountStatus.DELETED, h.repo.findById("acc_n")!!.status)
        assertNotNull(purgeAfter)
        h.deletion.requestConfirmation("acc_n", ses)   // nothing to mail, no error, no oracle
        assertEquals(0, h.mailer.of(MailKind.DELETE_CODE).size)
    }

    @Test
    fun `unlinking a sign-in method needs the re-authentication of the account kind`() {
        emailless()
        val ids = AccountSignInService(h.core, registry).identities
        val google = h.repo.findIdentity("google", "g-user-1")!!
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { ids.unlink("acc_n", google.id, ses, ReauthInput()) })
        assertEquals(2, h.repo.identitiesOf("acc_n").size)
        ids.unlink("acc_n", google.id, ses, good)
        assertEquals(listOf("naver"), h.repo.identitiesOf("acc_n").map { it.method })
        assertTrue(h.revoker.calls.any { it == ("acc_n" to ses) })
    }

    @Test
    fun `unlinking asks a password account for its password and a passwordless one with an address for a mailed code`() {
        val a = h.activeAccount()
        val ids = AccountSignInService(h.core, registry).identities
        ids.link(a.id, "google", "g-1", verified = true)
        val google = h.repo.findIdentity("google", "g-1")!!
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { ids.unlink(a.id, google.id, ses, ReauthInput()) })
        assertEquals("ACCOUNT.CURRENT_PASSWORD_INVALID", code { ids.unlink(a.id, google.id, ses, ReauthInput("wrong-password-1")) })
        ids.unlink(a.id, google.id, ses, ReauthInput("tangerine-42-moon"))
        assertEquals(listOf("password"), h.repo.identitiesOf(a.id).map { it.method })

        val now = h.time.now()
        h.repo.insert(Account("acc_m", "m@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now), listOf(Identity("idn_m1", "acc_m", "google", "g-m", true, createdAt = now), Identity("idn_m2", "acc_m", "naver", "n-m", true, createdAt = now)))
        assertEquals("ACCOUNT.REAUTH_REQUIRED", code { ids.unlink("acc_m", "idn_m1", ses, ReauthInput()) })
        h.reauth.requestConfirmation("acc_m", ses)
        val sent = h.mailer.of(MailKind.REAUTH_CODE).single().vars.getValue("code")
        ids.unlink("acc_m", "idn_m1", ses, ReauthInput(confirmationCode = sent))
        assertEquals(listOf("naver"), h.repo.identitiesOf("acc_m").map { it.method })
    }
}
