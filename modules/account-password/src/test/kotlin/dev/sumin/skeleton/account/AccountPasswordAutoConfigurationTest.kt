package dev.sumin.skeleton.account

import dev.sumin.skeleton.account.mail.AccountMailAutoConfiguration
import dev.sumin.skeleton.account.password.AccountPasswordAutoConfiguration
import dev.sumin.skeleton.account.password.PasswordHasher
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.login.LoginHooks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.crypto.password.PasswordEncoder

class AccountPasswordAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(AccountMailAutoConfiguration::class.java, AccountAutoConfiguration::class.java, AccountPasswordAutoConfiguration::class.java),
    ).withPropertyValues("skeleton.account.password.bcrypt-strength=4")

    @Test
    fun `the module adds the password method, the credentials port, the reset-link hook and the login hooks to an account context`() {
        runner.run { ctx ->
            assertNotNull(ctx.getBean(RegistrationService::class.java))
            assertNotNull(ctx.getBean(PasswordService::class.java))
            assertNotNull(ctx.getBean(SignInMethodRegistry::class.java).find(SignInMethods.PASSWORD))
            assertSame(ctx.getBean(PasswordHasher::class.java), ctx.getBean(PasswordCredentials::class.java), "the port is the hasher")
            assertNotNull(ctx.getBean(ResetLinkIssuer::class.java))
            assertEquals(setOf("loginThrottle", "loginRecorder"), ctx.getBeansOfType(LoginHooks::class.java).keys)
            assertTrue(ctx.getBean(PasswordEncoder::class.java).matches("x", ctx.getBean(PasswordEncoder::class.java).encode("x")))
        }
    }

    @Test
    fun `seed accounts get a real password hash when the module is there`() {
        runner.withPropertyValues(
            "skeleton.account.seed.accounts[0].email=Mod@Example.com", "skeleton.account.seed.accounts[0].password=password-1234",
        ).run { ctx ->
            ctx.getBean(AccountSeeder::class.java).seed()
            val auth = ctx.getBean(AuthAccountRepository::class.java).findBy(dev.sumin.skeleton.auth.account.AccountIdentifier(email = "mod@example.com"))!!
            assertTrue(ctx.getBean(PasswordHasher::class.java).matches("password-1234", auth.passwordHash))
        }
    }

    @Test
    fun `the core sees the port and the hook - and reset links are built by the module`() {
        runner.run { ctx ->
            val core = ctx.getBean(AccountCore::class.java)
            assertNotNull(core.passwords())
            assertNotNull(core.resetLinks())
        }
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AccountMailAutoConfiguration::class.java, AccountAutoConfiguration::class.java)).run { ctx ->
            val core = ctx.getBean(AccountCore::class.java)
            assertNull(core.passwords())
            assertNull(core.resetLinks())
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
