package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import dev.sumin.skeleton.payment.PaymentCheckoutGateway
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentLookupGateway
import dev.sumin.skeleton.payment.PaymentProviderRouter
import dev.sumin.skeleton.payment.PaymentWebhookGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 조립: 한 빈이 모든 능력을 갖고(라우터에 결제사가 둘로 보이지 않는다), 가드는 켠 순간부터 서며, 시크릿이 비면 제공자 빈만 없다. */
class TossPaymentWiringTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                OutboundHttpAutoConfiguration::class.java,
                PaymentAutoConfiguration::class.java,
                TossPaymentAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `one provider bean answers for every capability, and the router finds each under the same id`() {
        runner
            .withPropertyValues(
                "skeleton.payment-toss.enabled=true",
                "skeleton.payment-toss.secret-key=test_sk_123",
                "skeleton.payment-toss.client-key=test_ck_123",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBeansOfType(TossPaymentProvider::class.java)).hasSize(1)
                val router = context.getBean(PaymentProviderRouter::class.java)
                assertThat(router.gateway("toss", PaymentLookupGateway::class)).isSameAs(context.getBean(TossPaymentProvider::class.java))
                assertThat(router.gateway("toss", PaymentCheckoutGateway::class)).isNotNull
                assertThat(router.gateway("toss", PaymentWebhookGateway::class)).isNotNull
            }
    }

    @Test
    fun `enabled without a secret key boots with no provider bean - and the guard is there to warn`() {
        runner
            .withPropertyValues("skeleton.payment-toss.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(TossPaymentProvider::class.java)
                assertThat(context).hasSingleBean(TossKeyGuard::class.java)
                val guard = context.getBean(TossKeyGuard::class.java)
                assertThat(guard.warnings(dev.sumin.skeleton.common.deploy.DeployContext(null, emptySet()))).hasSize(1)
            }
    }

    @Test
    fun `the guard is a DeployGuard bean only while the module is enabled`() {
        runner.run { context ->
            assertThat(context).doesNotHaveBean(TossKeyGuard::class.java)
        }
        runner
            .withPropertyValues("skeleton.payment-toss.enabled=true", "skeleton.payment-toss.secret-key=test_sk_1")
            .run { context ->
                assertThat(context.getBeansOfType(DeployGuard::class.java).values.map { it.name }).contains("payment-toss")
            }
    }

    @Test
    fun `new keys bind from properties with their defaults`() {
        runner
            .withPropertyValues(
                "skeleton.payment-toss.enabled=true",
                "skeleton.payment-toss.secret-key=test_sk_1",
            )
            .run { context ->
                val properties = context.getBean(TossPaymentProperties::class.java)
                assertThat(properties.clientKey).isEmpty()
                assertThat(properties.lookupPath).isEqualTo("/v1/payments/{paymentKey}")
                assertThat(properties.lookupByOrderPath).isEqualTo("/v1/payments/orders/{orderId}")
                assertThat(properties.lookupTimeout).isEqualTo(java.time.Duration.ofSeconds(10))
                assertThat(properties.allowTestKeysInProtectedEnv).isFalse()
            }
        runner
            .withPropertyValues(
                "skeleton.payment-toss.enabled=true",
                "skeleton.payment-toss.secret-key=test_sk_1",
                "skeleton.payment-toss.lookup-timeout=3s",
                "skeleton.payment-toss.allow-test-keys-in-protected-env=true",
                "skeleton.payment-toss.client-key=test_ck_1",
            )
            .run { context ->
                val properties = context.getBean(TossPaymentProperties::class.java)
                assertThat(properties.lookupTimeout).isEqualTo(java.time.Duration.ofSeconds(3))
                assertThat(properties.allowTestKeysInProtectedEnv).isTrue()
                assertThat(properties.clientKey).isEqualTo("test_ck_1")
            }
    }

    @Test
    fun `the ledger is recognised by the type of a bean - a type reference, not a class name string`() {
        ApplicationContextRunner().withBean(FakeLedger::class.java).run { context ->
            val beanFactory = context.autowireCapableBeanFactory as org.springframework.beans.factory.ListableBeanFactory
            assertThat(LedgerPresence.present(beanFactory, FakeLedger::class.java)).isTrue()
            assertThat(LedgerPresence.present(beanFactory)).isFalse()
        }
    }

    @Test
    fun `the guard reads the reconcile first delay and the refund settle delay from the bound payment properties`() {
        val ledger = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PaymentLedgerStore::class.java)) { _, _, _ -> error("the guard never touches the store") } as PaymentLedgerStore
        val local = dev.sumin.skeleton.common.deploy.DeployContext(dev.sumin.skeleton.common.deploy.DeployEnv.LOCAL, emptySet())
        val base = arrayOf(
            "skeleton.payment-toss.enabled=true",
            "skeleton.payment-toss.secret-key=test_sk_1",
            "skeleton.payment-toss.client-key=test_ck_1",
            "skeleton.http.clients.payment-toss.response-timeout=10s",
        )

        runner.withBean(PaymentLedgerStore::class.java, java.util.function.Supplier { ledger })
            .withPropertyValues(*base, "skeleton.payment.reconcile.first-delay=4s", "skeleton.payment.refund.settle-delay=5s")
            .run { context ->
                val problems = context.getBean(TossKeyGuard::class.java).problems(local)
                assertThat(problems.filter { "skeleton.payment.reconcile.first-delay" in it }).hasSize(1)
                assertThat(problems.filter { "skeleton.payment.refund.settle-delay" in it }).hasSize(1)
            }
        runner.withBean(PaymentLedgerStore::class.java, java.util.function.Supplier { ledger })
            .withPropertyValues(*base, "skeleton.payment.reconcile.first-delay=11s", "skeleton.payment.refund.settle-delay=11s")
            .run { context -> assertThat(context.getBean(TossKeyGuard::class.java).problems(local)).isEmpty() }
        runner.withPropertyValues(*base, "skeleton.payment.refund.settle-delay=1s")
            .run { context -> assertThat(context.getBean(TossKeyGuard::class.java).problems(local)).describedAs("no ledger, no settle job").isEmpty() }
    }

    private class FakeLedger
}
