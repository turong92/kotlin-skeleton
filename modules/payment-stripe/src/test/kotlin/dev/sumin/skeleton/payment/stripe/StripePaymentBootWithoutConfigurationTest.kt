package dev.sumin.skeleton.payment.stripe

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 모듈 + 선언된 의존(payment, platform)만 얹고 설정이 없으면 뜬다 — 시크릿 키 없이, 제공자는 `enabled=true` 로 켠다. */
class StripePaymentBootWithoutConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                OutboundHttpAutoConfiguration::class.java,
                PaymentAutoConfiguration::class.java,
                StripePaymentAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `boots with no configuration, and the provider appears when enabled with a key`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean("stripePaymentProvider")
        }
        runner.withPropertyValues("skeleton.payment-stripe.enabled=true", "skeleton.payment-stripe.secret-key=sk_test_123")
            .run { context -> assertThat(context).hasSingleBean(StripePaymentProvider::class.java) }
    }
}
