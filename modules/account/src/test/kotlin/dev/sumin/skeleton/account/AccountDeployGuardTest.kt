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
    private val healthy = AccountDeployGuard.State(RealRepo, RealTokens, mailTransportIsLogOnly = false, captchaAvailable = true)
    private val goodProps = AccountProperties(mail = AccountProperties.Mail(linkBaseUrl = "https://app.example.com"))
    private fun guard(props: AccountProperties = goodProps, state: AccountDeployGuard.State = healthy) = AccountDeployGuard(props, listOf("prod", "staging")) { state }

    @Test
    fun `a healthy production setup has no problems`() {
        assertEquals(emptyList(), guard().problems(prod))
    }

    @Test
    fun `in-memory stores are problems in a protected env and in a protected profile, never in local or unset`() {
        val mem = healthy.copy(repository = InMemoryAccountRepository(), tokenStore = InMemoryOneTimeTokenStore())
        val messages = guard(state = mem).problems(prod)
        assertEquals(2, messages.size)
        assertTrue(messages.any { "AccountRepository" in it } && messages.any { "OneTimeTokenStore" in it })
        assertEquals(2, guard(state = mem).problems(stage).size)
        assertEquals(2, guard(state = mem).problems(prodProfile).size)
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
            seed = AccountProperties.Seed(listOf(AccountProperties.SeedAccount("admin@example.com", "password"))),
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
        val props = goodProps.copy(seed = AccountProperties.Seed(listOf(AccountProperties.SeedAccount("admin@example.com", "SeedPassw0rd!"))))
        val text = guard(props).problems(prod).joinToString() + guard(props).warnings(prod).joinToString()
        assertTrue("SeedPassw0rd" !in text && "admin@example.com" !in text && "app.example.com" !in text)
    }

    @Test
    fun `open sign-up without captcha is only a warning`() {
        val w = guard().warnings(prod)
        assertTrue(w.any { "captcha" in it })
        assertEquals(emptyList(), guard().problems(prod))
    }
}
