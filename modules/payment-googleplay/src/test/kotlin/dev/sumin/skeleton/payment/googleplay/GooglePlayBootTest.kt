package dev.sumin.skeleton.payment.googleplay

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class GooglePlayBootTest {
    private val runner = ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, GooglePlayPaymentAutoConfiguration::class.java),
    )
    private val account = TestServiceAccount()
    private val secrets = arrayOf(
        "skeleton.payment-googleplay.enabled=true",
        "skeleton.payment-googleplay.package-name=$PACKAGE",
        "skeleton.payment-googleplay.service-account=${account.base64}",
        "skeleton.payment-googleplay.pubsub.audience=$AUDIENCE",
        "skeleton.payment-googleplay.pubsub.service-account-email=$PUSH_EMAIL",
    )
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())

    private fun without(key: String) = secrets.filterNot { it.contains(key) }.toTypedArray()

    @Test
    fun `boots with nothing, and a missing value switches only this store off`() {
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(GooglePlayPaymentProvider::class.java) }
        runner.withPropertyValues(*without("package-name")).run { assertThat(it).hasNotFailed().doesNotHaveBean(GooglePlayPaymentProvider::class.java) }
        runner.withPropertyValues(*secrets).run { assertThat(it).hasSingleBean(GooglePlayPaymentProvider::class.java) }
    }

    @Test
    fun `a service account that cannot be read switches the store off too`() {
        runner.withPropertyValues(*without("service-account="), "skeleton.payment-googleplay.service-account=not-a-key")
            .run { assertThat(it).hasNotFailed().doesNotHaveBean(GooglePlayPaymentProvider::class.java) }
    }

    @Test
    fun `a protected environment does not start with a value missing or unreadable, a local one only warns`() {
        for (key in listOf("package-name", "service-account=", "pubsub.audience", "pubsub.service-account-email")) {
            runner.withPropertyValues(*without(key)).run {
                val guard = it.getBean("googlePlayGuard", DeployGuard::class.java)
                assertThat(guard.problems(stage)).`as`(key).singleElement().asString().contains("skeleton.payment-googleplay.")
                assertThat(guard.problems(local)).isEmpty()
                assertThat(guard.warnings(local)).isNotEmpty()
            }
        }
        runner.withPropertyValues(*without("service-account="), "skeleton.payment-googleplay.service-account=not-a-key").run {
            assertThat(it.getBean("googlePlayGuard", DeployGuard::class.java).problems(stage)).singleElement().asString().contains("service-account").doesNotContain("not-a-key")
        }
        runner.withPropertyValues(*secrets).run { assertThat(it.getBean("googlePlayGuard", DeployGuard::class.java).problems(stage)).isEmpty() }
    }

    @Test
    fun `a certificate or token address that is not Google's stops a protected environment and never prints the value`() {
        for (key in listOf("certs-url", "token-url")) {
            runner.withPropertyValues(*secrets, "skeleton.payment-googleplay.$key=https://proxy.example.com/SECRET-PATH").run {
                val guard = it.getBean("googlePlayGuard", DeployGuard::class.java)
                assertThat(guard.problems(stage)).`as`(key).singleElement().asString().contains("skeleton.payment-googleplay.$key").doesNotContain("SECRET-PATH")
                assertThat(guard.problems(local)).isEmpty()
            }
        }
    }

    @Test
    fun `a Google subdomain that is not the fixed certificate or token address still stops a protected environment`() {
        for ((key, url) in listOf("certs-url" to "https://storage.googleapis.com/some-bucket/certs", "token-url" to "https://www.googleapis.com/oauth2/v4/token")) {
            runner.withPropertyValues(*secrets, "skeleton.payment-googleplay.$key=$url").run {
                assertThat(it.getBean("googlePlayGuard", DeployGuard::class.java).problems(stage)).`as`(key).singleElement().asString().contains(key)
            }
        }
    }

    @Test
    fun `an API address that is not Google's is a warning in a protected environment`() {
        runner.withPropertyValues(*secrets, "skeleton.payment-googleplay.base-url=https://proxy.example.com/SECRET-PATH").run {
            val guard = it.getBean("googlePlayGuard", DeployGuard::class.java)
            assertThat(guard.problems(stage)).isEmpty()
            assertThat(guard.warnings(stage)).singleElement().asString().contains("skeleton.payment-googleplay.base-url").doesNotContain("SECRET-PATH")
            assertThat(guard.warnings(local)).isEmpty()
        }
    }

    @Test
    fun `the secrets never appear in the settings text`() {
        val text = GooglePlayProperties(enabled = true, packageName = PACKAGE, serviceAccount = account.base64).toString()
        assertThat(text).doesNotContain(account.base64)
    }
}
