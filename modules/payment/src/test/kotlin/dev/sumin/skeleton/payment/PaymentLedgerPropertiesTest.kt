package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class PaymentLedgerPropertiesTest {
    private fun bind(vararg pairs: Pair<String, String>): PaymentProperties =
        Binder(MapConfigurationPropertySource(mapOf(*pairs)))
            .bind("skeleton.payment", Bindable.of(PaymentProperties::class.java))
            .orElseGet { PaymentProperties() }

    @Test
    fun `a zero or negative duration or a zero attempt count is refused at startup`() {
        assertFailsWith<BindException> { bind("skeleton.payment.checkout-ttl" to "0s") }
        assertFailsWith<BindException> { bind("skeleton.payment.reconcile.max-attempts" to "0") }
    }

    @Test
    fun `the webhook body limit stays within one MiB - a limit near Int MAX_VALUE is refused at startup`() {
        assertEquals(1_048_576, bind("skeleton.payment.webhook.max-body-bytes" to "1048576").webhook.maxBodyBytes)
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.max-body-bytes" to "1048577") }
    }
}
