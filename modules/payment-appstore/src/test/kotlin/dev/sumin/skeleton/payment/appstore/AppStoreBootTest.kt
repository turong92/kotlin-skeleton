package dev.sumin.skeleton.payment.appstore

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class AppStoreBootTest {
    private val runner = ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, AppStorePaymentAutoConfiguration::class.java),
    )
    private val enabled = "skeleton.payment-appstore.enabled=true"
    private val secrets = arrayOf(
        enabled,
        "skeleton.payment-appstore.bundle-id=$BUNDLE",
        "skeleton.payment-appstore.app-apple-id=$APP_APPLE_ID",
        "skeleton.payment-appstore.issuer-id=$ISSUER",
        "skeleton.payment-appstore.key-id=$KEY_ID",
        "skeleton.payment-appstore.private-key=PRIVATE-KEY-VALUE",
    )
    private val stage = DeployContext(DeployEnv.STAGE, emptySet())
    private val local = DeployContext(DeployEnv.LOCAL, emptySet())

    @Test
    fun `boots with nothing, and a missing value switches only this store off`() {
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(AppStorePaymentProvider::class.java).doesNotHaveBean(AppStoreSignatureVerifier::class.java) }
        runner.withPropertyValues(*secrets.filterNot { it.contains("key-id") }.toTypedArray())
            .run { assertThat(it).hasNotFailed().doesNotHaveBean(AppStorePaymentProvider::class.java) }
        runner.withPropertyValues(*secrets).run {
            assertThat(it).hasSingleBean(AppStorePaymentProvider::class.java).hasSingleBean(AppStoreSignatureVerifier::class.java)
        }
    }

    @Test
    fun `an app can replace the verifier bean - there is no setting for the root`() {
        val custom = verifier(TestChain.create())
        runner.withPropertyValues(*secrets).withBean(AppStoreSignatureVerifier::class.java, { custom })
            .run { assertThat(it.getBean(AppStoreSignatureVerifier::class.java)).isSameAs(custom) }
    }

    @Test
    fun `a protected environment does not start with a value missing, a local one only warns`() {
        runner.withPropertyValues(*secrets.filterNot { it.contains("private-key") }.toTypedArray()).run {
            val guard = it.getBean("appStoreGuard", DeployGuard::class.java)
            assertThat(guard.problems(stage)).singleElement().asString().contains("skeleton.payment-appstore.private-key")
            assertThat(guard.problems(local)).isEmpty()
            assertThat(guard.warnings(local)).anyMatch { w -> w.contains("skeleton.payment-appstore.private-key") }
        }
        runner.withPropertyValues(*secrets).run {
            val guard = it.getBean("appStoreGuard", DeployGuard::class.java)
            assertThat(guard.problems(stage)).isEmpty()
        }
    }

    @Test
    fun `a base url that is not Apple's is a warning in a protected environment and never prints the value`() {
        runner.withPropertyValues(*secrets, "skeleton.payment-appstore.base-url=https://proxy.example.com/SECRET-PATH").run {
            val guard = it.getBean("appStoreGuard", DeployGuard::class.java)
            assertThat(guard.warnings(stage)).singleElement().asString().contains("skeleton.payment-appstore.base-url").doesNotContain("SECRET-PATH")
            assertThat(guard.warnings(local)).isEmpty()
        }
        runner.withPropertyValues(*secrets).run { assertThat(it.getBean("appStoreGuard", DeployGuard::class.java).warnings(stage)).isEmpty() }
    }

    @Test
    fun `the secret values never appear in the settings text`() {
        val text = properties().toString()
        assertThat(text).doesNotContain("PRIVATE KEY").doesNotContain(ISSUER)
    }
}
