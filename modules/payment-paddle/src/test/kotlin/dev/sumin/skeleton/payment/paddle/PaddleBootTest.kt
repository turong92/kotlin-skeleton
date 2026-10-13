package dev.sumin.skeleton.payment.paddle

import dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration
import dev.sumin.skeleton.payment.PaymentAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PaddleBootTest {
    private val runner = ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(OutboundHttpAutoConfiguration::class.java, PaymentAutoConfiguration::class.java, PaddlePaymentAutoConfiguration::class.java),
    )
    private val enabled = "skeleton.payment-paddle.enabled=true"

    @Test
    fun `boots with nothing, and an empty secret switches only this provider off`() {
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(PaddlePaymentProvider::class.java) }
        runner.withPropertyValues(enabled, "skeleton.payment-paddle.api-key=k", "skeleton.payment-paddle.client-token=t")
            .run { assertThat(it).hasNotFailed().doesNotHaveBean(PaddlePaymentProvider::class.java) } // webhook secret missing
        runner.withPropertyValues(enabled, "skeleton.payment-paddle.api-key=k", "skeleton.payment-paddle.client-token=t", "skeleton.payment-paddle.webhook-secret=s")
            .run { assertThat(it).hasSingleBean(PaddlePaymentProvider::class.java) }
    }
}
