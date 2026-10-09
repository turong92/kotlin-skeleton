package dev.sumin.skeleton.payment

import java.time.Duration
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
    fun `defaults are the ones the design fixed - 30 minutes, 60 seconds, 12 attempts`() {
        val properties = bind()

        assertEquals(Duration.ofMinutes(30), properties.checkoutTtl)
        assertEquals(Duration.ofSeconds(60), properties.reconcile.firstDelay)
        assertEquals(12, properties.reconcile.maxAttempts)
    }

    @Test
    fun `the ledger keys bind under the one skeleton payment prefix and the old keys still bind`() {
        val properties = bind(
            "skeleton.payment.checkout-ttl" to "10m",
            "skeleton.payment.reconcile.first-delay" to "45s",
            "skeleton.payment.reconcile.max-attempts" to "5",
            "skeleton.payment.default-provider" to "toss",
        )

        assertEquals(Duration.ofMinutes(10), properties.checkoutTtl)
        assertEquals(Duration.ofSeconds(45), properties.reconcile.firstDelay)
        assertEquals(5, properties.reconcile.maxAttempts)
        assertEquals("toss", properties.defaultProvider)
    }

    @Test
    fun `a zero or negative duration or a zero attempt count is refused at startup`() {
        assertFailsWith<BindException> { bind("skeleton.payment.checkout-ttl" to "0s") }
        assertFailsWith<BindException> { bind("skeleton.payment.reconcile.first-delay" to "-5s") }
        assertFailsWith<BindException> { bind("skeleton.payment.reconcile.max-attempts" to "0") }
    }

    @Test
    fun `the redelivery requeue limit defaults to 10, binds, and may be zero but not negative`() {
        assertEquals(10, bind().webhook.redeliveryRequeueLimit)
        assertEquals(3, bind("skeleton.payment.webhook.redelivery-requeue-limit" to "3").webhook.redeliveryRequeueLimit)
        assertEquals(0, bind("skeleton.payment.webhook.redelivery-requeue-limit" to "0").webhook.redeliveryRequeueLimit)
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.redelivery-requeue-limit" to "-1") }
    }

    @Test
    fun `the per-order webhook limit defaults to 30 events in 10 minutes and binds`() {
        assertEquals(30, bind().webhook.perOrderLimit.capacity)
        assertEquals(Duration.ofMinutes(10), bind().webhook.perOrderLimit.window)

        val bound = bind("skeleton.payment.webhook.per-order-limit.capacity" to "5", "skeleton.payment.webhook.per-order-limit.window" to "2m")

        assertEquals(5, bound.webhook.perOrderLimit.capacity)
        assertEquals(Duration.ofMinutes(2), bound.webhook.perOrderLimit.window)
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.per-order-limit.capacity" to "0") }
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.per-order-limit.window" to "0s") }
    }

    @Test
    fun `the webhook body limit stays within one MiB - a limit near Int MAX_VALUE is refused at startup`() {
        assertEquals(1_048_576, bind("skeleton.payment.webhook.max-body-bytes" to "1048576").webhook.maxBodyBytes)
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.max-body-bytes" to "1048577") }
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.max-body-bytes" to "${Int.MAX_VALUE}") }
        assertFailsWith<BindException> { bind("skeleton.payment.webhook.max-body-bytes" to "0") }
    }
}
