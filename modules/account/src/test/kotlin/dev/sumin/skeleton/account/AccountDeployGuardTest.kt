package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.token.InMemoryOneTimeTokenStore
import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountDeployGuardTest {
    private val prod = DeployContext(DeployEnv.PROD, emptySet())
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val prodProfile = DeployContext(null, setOf("prod"))
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())
    private val unset = DeployContext(null, emptySet())

    private object RealRepo : AccountRepository by InMemoryAccountRepository()
    private object RealTokens
    private val healthy = AccountDeployGuard.State(RealRepo, RealTokens, RealTokens, mailTransportIsLogOnly = false, captchaAvailable = true, clientIpModeConfigured = true)
    private val goodProps = AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"))
    private fun guard(props: AccountProperties = goodProps, state: AccountDeployGuard.State = healthy) = AccountDeployGuard(props, listOf("prod", "staging")) { state }

    @Test
    fun `a healthy production setup has no problems`() {
        assertEquals(emptyList(), guard().problems(prod))
    }

    @Test
    fun `in-memory stores are problems in a protected env and in a protected profile, never in local or unset`() {
        val mem = healthy.copy(repository = InMemoryAccountRepository(), tokenStore = InMemoryOneTimeTokenStore(), challengeStore = dev.sumin.skeleton.account.challenge.InMemoryChallengeStore())
        val messages = guard(state = mem).problems(prod)
        assertEquals(3, messages.size)
        assertTrue(messages.any { "AccountRepository" in it } && messages.any { "OneTimeTokenStore" in it } && messages.any { "ChallengeStore" in it })
        assertEquals(3, guard(state = mem).problems(stage).size)
        assertEquals(3, guard(state = mem).problems(prodProfile).size)
        assertEquals(emptyList(), guard(state = mem).problems(local))
        assertEquals(emptyList(), guard(state = mem).problems(unset))
    }

    @Test
    fun `no mail transport and no link base url are problems`() {
        val problems = guard(AccountProperties(), healthy.copy(mailTransportIsLogOnly = true)).problems(prod)
        assertTrue(problems.any { "mail transport" in it })
        assertTrue(problems.any { "link-base-url" in it })
    }

    @Test
    fun `logging links, seed accounts, an unmet captcha requirement and a bad bootstrap address are problems`() {
        val props = goodProps.copy(
            mail = goodProps.mail.copy(logLinks = AccountProperties.Mail.LogLinks.ON),
            seed = AccountProperties.Seed(listOf(AccountProperties.SeedAccount(email = "admin@example.com", password = "password"))),
            captcha = AccountProperties.Captcha(required = true),
            bootstrap = AccountProperties.Bootstrap(adminEmail = "not-an-email"),
        )
        val problems = guard(props, healthy.copy(captchaAvailable = false)).problems(prod)
        assertEquals(4, problems.size, problems.toString())
        assertTrue(problems.any { "log-links" in it })
        assertTrue(problems.any { "seed" in it })
        assertTrue(problems.any { "captcha" in it })
        assertTrue(problems.any { "admin-email" in it })
        assertEquals(emptyList(), guard(props, healthy.copy(captchaAvailable = false)).problems(local))
    }

    @Test
    fun `messages name properties and modules, never values`() {
        val props = goodProps.copy(seed = AccountProperties.Seed(listOf(AccountProperties.SeedAccount(email = "admin@example.com", password = "SeedPassw0rd!"))))
        val text = guard(props).problems(prod).joinToString() + guard(props).warnings(prod).joinToString()
        assertTrue("SeedPassw0rd" !in text && "admin@example.com" !in text && "app.example.com" !in text)
    }

    @Test
    fun `no email verification together with a mailbox-proving method or social merging is called out - the squatter holds an active account until the owner proves the mailbox`() {
        val noVerify = goodProps.copy(signUp = AccountProperties.SignUp(emailVerification = false))
        val plain = guard(noVerify).warnings(prod)
        assertTrue(plain.any { "email-verification=false" in it && "409" in it })
        assertTrue(plain.none { "mailbox" in it }, "without a mailbox-proving method there is nothing to warn about beyond the 409")
        val withMagic = guard(noVerify, healthy.copy(mailboxProofMethod = true)).warnings(prod)
        assertTrue(withMagic.any { "mailbox" in it && "email-verification=false" in it }, withMagic.toString())
        val withMerge = guard(noVerify.copy(social = AccountProperties.Social(signUp = true, mergeOnVerifiedEmail = true))).warnings(prod)
        assertTrue(withMerge.any { "merge-on-verified-email" in it }, withMerge.toString())
        assertEquals(emptyList(), guard(noVerify, healthy.copy(mailboxProofMethod = true)).problems(prod), "a warning, not a problem: the proof removes what was planted")
        assertTrue(guard(goodProps, healthy.copy(mailboxProofMethod = true)).warnings(prod).none { "mailbox" in it })
    }

    @Test
    fun `open sign-up without captcha is only a warning`() {
        val w = guard().warnings(prod)
        assertTrue(w.any { "captcha" in it })
        assertEquals(emptyList(), guard().problems(prod))
    }

    @Test
    fun `without a client IP mode the IP limits can be rotated by any caller - a problem in protected envs only`() {
        val open = healthy.copy(clientIpModeConfigured = false)
        val problems = guard(state = open).problems(prod)
        assertEquals(1, problems.size, problems.toString())
        assertTrue("skeleton.web.client-ip.mode" in problems.single() && "X-Forwarded-For" in problems.single(), problems.single())
        assertEquals(1, guard(state = open).problems(prodProfile).size)
        assertEquals(emptyList(), guard(state = open).problems(local))
        assertEquals(emptyList(), guard(state = open).problems(unset))
    }

    @Test
    fun `an app with every IP limit off has nothing to protect and is not forced to pick a mode`() {
        val off = goodProps.copy(login = AccountProperties.Login(throttleEnabled = false), signUp = AccountProperties.SignUp(enabled = false))
        // forgot-password and resend are always limited per IP, so the mode stays required: only the two optional limits are off here
        assertTrue(guard(off, healthy.copy(clientIpModeConfigured = false)).problems(prod).any { "client-ip" in it })
    }
}
