package dev.sumin.skeleton.payment

import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PaymentAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PaymentAutoConfiguration::class.java))

    @Test
    fun `creates router and service when providers are present`() {
        contextRunner
            .withBean(PaymentProvider::class.java, Supplier { StubPaymentProvider("stripe") })
            .withPropertyValues("skeleton.payment.default-provider=stripe")
            .run { context ->
                assertEquals(1, context.getBeansOfType(PaymentProviderRouter::class.java).size)
                assertEquals(1, context.getBeansOfType(PaymentService::class.java).size)
            }
    }

    private class StubPaymentProvider(
        override val providerId: String,
    ) : PaymentProvider {
        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult =
            throw UnsupportedOperationException()

        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult =
            throw UnsupportedOperationException()

        override fun refund(request: PaymentRefundRequest): PaymentOperationResult =
            throw UnsupportedOperationException()
    }
}
