package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.abuse.AccountAlertAutoConfiguration
import dev.sumin.skeleton.account.abuse.AccountCaptcha
import dev.sumin.skeleton.account.abuse.AccountCaptchaAutoConfiguration
import dev.sumin.skeleton.account.events.AccountEventListener
import dev.sumin.skeleton.account.mail.AccountMailAutoConfiguration
import dev.sumin.skeleton.account.mail.AccountMailTransport
import dev.sumin.skeleton.account.mail.LogOnlyMailTransport
import dev.sumin.skeleton.account.mail.MailSenderTransport
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.captcha.turnstile.TurnstileProperties
import dev.sumin.skeleton.captcha.turnstile.TurnstileVerifier
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.notification.mail.MailMessage
import dev.sumin.skeleton.notification.mail.MailSendResult
import dev.sumin.skeleton.notification.mail.MailSender
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class AccountAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(AccountMailAutoConfiguration::class.java, AccountCaptchaAutoConfiguration::class.java, AccountAlertAutoConfiguration::class.java, AccountAutoConfiguration::class.java),
    ).withPropertyValues("skeleton.account.password.bcrypt-strength=4")

    @Test
    fun `the module boots with no configuration and no infrastructure and logs mail instead of sending it`() {
        runner.run { ctx ->
            assertTrue(ctx.getBean(AccountRepository::class.java) is InMemoryAccountRepository)
            assertTrue(ctx.getBean(AccountMailTransport::class.java) is LogOnlyMailTransport)
            assertTrue(ctx.getBean(AuthAccountRepository::class.java) is AccountAuthRepository)
            assertNotNull(ctx.getBean(RegistrationService::class.java))
        }
    }

    @Test
    fun `the module offers the author directory and an app bean of the same type replaces it`() {
        runner.run { ctx -> assertTrue(ctx.getBean(dev.sumin.skeleton.common.author.AuthorDirectory::class.java) is AccountAuthorDirectory) }
        val mine = dev.sumin.skeleton.common.author.AuthorDirectory { ids, _ -> ids.associateWith { dev.sumin.skeleton.common.author.AuthorCard("fan-$it") } }
        runner.withBean(dev.sumin.skeleton.common.author.AuthorDirectory::class.java, java.util.function.Supplier { mine }).run { ctx ->
            assertEquals(1, ctx.getBeansOfType(dev.sumin.skeleton.common.author.AuthorDirectory::class.java).size)
            assertTrue(ctx.getBean(dev.sumin.skeleton.common.author.AuthorDirectory::class.java) === mine)
        }
    }

    @Test
    fun `the nickname rules are a replaceable bean - the default is used until the app brings its own`() {
        runner.run { ctx -> assertTrue(ctx.getBean(AccountCore::class.java).names.rules === DefaultDisplayNameRules) }
        val mine = object : DisplayNameRules by DefaultDisplayNameRules {
            override fun key(cleaned: String) = "mine:" + cleaned
        }
        runner.withBean(DisplayNameRules::class.java, java.util.function.Supplier { mine }).run { ctx ->
            assertEquals(1, ctx.getBeansOfType(DisplayNameRules::class.java).size)
            assertTrue(ctx.getBean(AccountCore::class.java).names.rules === mine)
            assertEquals("mine:x", ctx.getBean(AccountCore::class.java).names.key("x"))
        }
    }

    @Test
    fun `the display name settings bind from skeleton account display-name`() {
        runner.withPropertyValues(
            "skeleton.account.display-name.uniqueness=tagged", "skeleton.account.display-name.required-on-sign-up=true",
            "skeleton.account.display-name.fallback=generated", "skeleton.account.display-name.reserved=admin,운영자",
        ).run { ctx ->
            val d = ctx.getBean(AccountProperties::class.java).displayName
            assertEquals(AccountProperties.DisplayName.Uniqueness.TAGGED, d.uniqueness)
            assertTrue(d.requiredOnSignUp)
            assertEquals(AccountProperties.DisplayName.Fallback.GENERATED, d.fallback)
            assertEquals(listOf("admin", "운영자"), d.reserved)
        }
        runner.run { ctx ->
            val d = ctx.getBean(AccountProperties::class.java).displayName
            assertEquals(AccountProperties.DisplayName(), d)
            assertEquals(AccountProperties.DisplayName.Uniqueness.NONE, d.uniqueness, "neutral defaults: nothing about nicknames changes unless the app asks")
            assertFalse(d.requiredOnSignUp)
            assertEquals(AccountProperties.DisplayName.Fallback.NONE, d.fallback)
            assertTrue(d.reserved.isEmpty())
        }
    }

    @Test
    fun `the code hash key is derived from the JWT secret with the account-code prefix - not the bare secret, not a constant`() {
        fun hashUnder(secret: String): String {
            var hash = ""
            runner
                .withBean(dev.sumin.skeleton.auth.config.AuthProperties::class.java, java.util.function.Supplier { dev.sumin.skeleton.auth.config.AuthProperties(jwt = dev.sumin.skeleton.auth.config.AuthProperties.Jwt(secret = secret)) })
                .run { ctx -> hash = ctx.getBean(dev.sumin.skeleton.account.challenge.CodeHasher::class.java).hash("challenge-1", "123456") }
            return hash
        }
        val a = hashUnder("jwt-secret-A-0123456789abcdef0123456789")
        val b = hashUnder("jwt-secret-B-0123456789abcdef0123456789")
        val derived = dev.sumin.skeleton.account.challenge.CodeHasher(("account-code/" + "jwt-secret-A-0123456789abcdef0123456789").toByteArray()).hash("challenge-1", "123456")
        val bare = dev.sumin.skeleton.account.challenge.CodeHasher("jwt-secret-A-0123456789abcdef0123456789".toByteArray()).hash("challenge-1", "123456")
        assertEquals(derived, a, "key = \"account-code/\" + jwt.secret")
        assertTrue(a != b, "another secret, another hash")
        assertTrue(a != bare, "the JWT secret itself is never used as a key for another purpose")
    }

    @Configuration(proxyBeanMethods = false)
    class AppRepo {
        @Bean fun repo(): AccountRepository = object : AccountRepository by InMemoryAccountRepository() {}
    }

    @Test
    fun `an app's own repository replaces the default and auth sees accounts through it`() {
        runner.withUserConfiguration(AppRepo::class.java).run { ctx ->
            assertEquals(1, ctx.getBeansOfType(AccountRepository::class.java).size)
            assertFalse(ctx.getBean(AccountRepository::class.java) is InMemoryAccountRepository)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class AppListener {
        @Bean fun appEvents(): AccountEventListener = AccountEventListener { }
    }

    @Test
    fun `an app's own event listener does not silence the default log line - it backs off by name only`() {
        runner.withUserConfiguration(AppListener::class.java).run { ctx ->
            assertTrue(ctx.containsBean("loggingAccountEventListener"), "registering any listener (audit, an app's own) made the default log line vanish")
            assertEquals(2, ctx.getBeansOfType(AccountEventListener::class.java).size)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class FakeMail {
        val sent = mutableListOf<MailMessage>()
        @Bean fun mailSender(): MailSender = MailSender { sent += it; MailSendResult(true) }
    }

    @Test
    fun `with notification-mail's sender the mail goes through it`() {
        runner.withUserConfiguration(FakeMail::class.java).run { ctx ->
            val transport = ctx.getBean(AccountMailTransport::class.java)
            assertTrue(transport is MailSenderTransport)
            assertTrue(transport.send("a@b.co", "Subject", "text", "<p>html</p>"))
            assertEquals(listOf("a@b.co"), ctx.getBean(FakeMail::class.java).sent.single().to)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class FakeTurnstile {
        @Bean fun verifier(): TurnstileVerifier {
            val http = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ExternalHttpClient::class.java)) { _, _, _ -> error("no network in this test") } as ExternalHttpClient
            return TurnstileVerifier(http, TurnstileProperties(enabled = true, secretKey = "x"))
        }
    }

    @Test
    fun `a turnstile verifier is bridged to the captcha hook, and a missing token fails without any network call`() {
        runner.withUserConfiguration(FakeTurnstile::class.java).run { ctx ->
            assertFalse(ctx.getBean(AccountCaptcha::class.java).verify(null, "203.0.113.1", "sign_up"))
        }
        runner.run { ctx -> assertTrue(ctx.getBeansOfType(AccountCaptcha::class.java).isEmpty()) }
    }

    @Configuration(proxyBeanMethods = false)
    class Alerts { @Bean fun alerts(): OwnerAlerts = OwnerAlerts.NONE }

    @Test
    fun `owner alerts are wired in only when the alert module has a bean`() {
        runner.withUserConfiguration(Alerts::class.java).run { ctx -> assertTrue(ctx.containsBean("alertingAccountEventListener")) }
        runner.run { ctx -> assertFalse(ctx.containsBean("alertingAccountEventListener")) }
        runner.run { ctx -> assertTrue(ctx.getBeansOfType(AccountEventListener::class.java).isNotEmpty(), "the logging listener is always there") }
    }

    @Test
    fun `seed accounts are created once, verified and active, with the roles asked for`() {
        runner.withPropertyValues(
            "skeleton.account.seed.accounts[0].id=acc_moderator", "skeleton.account.seed.accounts[0].email=Mod@Example.com", "skeleton.account.seed.accounts[0].password=password-1234",
            "skeleton.account.seed.accounts[0].roles[0]=MODERATOR", "skeleton.account.seed.accounts[0].display-name=Moderator",
        ).run { ctx ->
            val seeder = ctx.getBean(AccountSeeder::class.java)
            seeder.seed(); seeder.seed()
            val repo = ctx.getBean(AccountRepository::class.java)
            assertEquals(1, repo.search(null, null, 0, 10).total)
            val account = repo.findByEmail("mod@example.com")!!
            assertEquals("acc_moderator", account.id, "a fixed id keeps e2e fixtures stable")
            assertEquals(AccountStatus.ACTIVE, account.status)
            assertTrue(account.emailVerified)
            assertEquals(setOf("USER", "MODERATOR"), account.roles)
            val auth = ctx.getBean(AuthAccountRepository::class.java).findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(email = "mod@example.com"))!!
            assertTrue(ctx.getBean(dev.sumin.skeleton.account.password.PasswordHasher::class.java).matches("password-1234", auth.passwordHash))
        }
    }

    @Test
    fun `a sign-up consent gate and an account transaction from another module reach the registration service`() {
        val checked = java.util.concurrent.CopyOnWriteArrayList<List<dev.sumin.skeleton.common.consent.ConsentClaim>>()
        val gate = object : dev.sumin.skeleton.common.consent.SignUpConsentGate {
            override fun check(claims: List<dev.sumin.skeleton.common.consent.ConsentClaim>) { checked += claims }
            override fun record(accountId: String, claims: List<dev.sumin.skeleton.common.consent.ConsentClaim>, context: dev.sumin.skeleton.common.consent.ConsentContext) = Unit
        }
        val ran = java.util.concurrent.atomic.AtomicInteger()
        val tx = object : AccountTransaction {
            override fun <T> run(block: () -> T): T { ran.incrementAndGet(); return block() }
        }
        runner.withBean(dev.sumin.skeleton.common.consent.SignUpConsentGate::class.java, java.util.function.Supplier { gate })
            .withBean(AccountTransaction::class.java, java.util.function.Supplier { tx })
            .run { ctx ->
                val registration = ctx.getBean(RegistrationService::class.java)
                val claim = dev.sumin.skeleton.common.consent.ConsentClaim("terms", "v1")
                val outcome = registration.signUp(SignUpCommand("ann@example.com", "tangerine-42-moon", null, null, null, "203.0.113.1", null, consents = listOf(claim)))

                assertEquals(listOf(listOf(claim)), checked)
                assertEquals(0, ran.get(), "nothing is created before the code is entered")
                assertNotNull(outcome.signUpId)
            }
    }

    @Test
    fun `without a gate bean the registration service ignores consents and the default transaction just runs the block`() {
        runner.run { ctx ->
            val claim = dev.sumin.skeleton.common.consent.ConsentClaim("terms", "v1")
            ctx.getBean(RegistrationService::class.java).signUp(SignUpCommand("ann@example.com", "tangerine-42-moon", null, null, null, "203.0.113.1", null, consents = listOf(claim)))
        }
    }
}
