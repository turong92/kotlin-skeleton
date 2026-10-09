package dev.sumin.skeleton.payment

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaymentGatewayCapabilityTest {
    private val router = PaymentProviderRouter(
        providers = listOf(
            LegacyProvider("toss"),
            LookupOnlyGateway("lookup-only"),
        ),
        properties = PaymentProperties(),
    )

    @Test
    fun `a payment provider is a confirm gateway and a refund gateway`() {
        val provider: PaymentProvider = LegacyProvider("toss")

        val confirm: PaymentConfirmGateway = provider
        val refund: PaymentRefundGateway = provider
        assertSame<Any>(confirm, refund)
        assertEquals(PaymentMode.UNKNOWN, provider.mode)
    }

    @Test
    fun `operation result remaining defaults to null`() {
        val result = PaymentOperationResult(
            provider = "toss",
            providerPaymentId = "pay_1",
            status = PaymentOperationStatus.REFUNDED,
            trace = ProviderTrace(provider = "toss"),
        )

        assertNull(result.remaining)
        assertEquals(
            PaymentAmount(1_000, "KRW"),
            result.copy(remaining = PaymentAmount(1_000, "KRW")).remaining,
        )
    }

    @Test
    fun `gateway returns the provider when it implements the capability`() {
        val confirm = router.gateway("toss", PaymentConfirmGateway::class)
        val refund = router.gateway(" Toss ", PaymentRefundGateway::class)

        assertSame<Any>(confirm, refund)
        assertEquals("toss", confirm.providerId)
    }

    @Test
    fun `gateway returns a gateway that implements only one capability`() {
        val lookup = router.gateway("lookup-only", PaymentLookupGateway::class)

        assertNull(lookup.lookup(PaymentLookupQuery(providerPaymentId = "pay_1", orderId = null)))
    }

    @Test
    fun `gateway fails with capability unsupported when the provider lacks the capability`() {
        val missingLookup = assertFailsWith<PaymentCapabilityUnsupportedException> {
            router.gateway("toss", PaymentLookupGateway::class)
        }
        val missingConfirm = assertFailsWith<PaymentCapabilityUnsupportedException> {
            router.gateway("lookup-only", PaymentConfirmGateway::class)
        }

        assertEquals("PAYMENT.CAPABILITY_UNSUPPORTED", missingLookup.errorCode.code)
        assertEquals(400, missingLookup.status.value())
        assertEquals("PAYMENT.CAPABILITY_UNSUPPORTED", missingConfirm.errorCode.code)
    }

    @Test
    fun `gateway fails with provider not found for an unknown or disabled provider`() {
        assertFailsWith<PaymentProviderNotFoundException> {
            router.gateway("nope", PaymentConfirmGateway::class)
        }
        val disabled = PaymentProviderRouter(
            providers = listOf(LegacyProvider("toss")),
            properties = PaymentProperties(
                providers = mapOf("toss" to PaymentProperties.Provider(enabled = false)),
            ),
        )
        assertFailsWith<PaymentProviderNotFoundException> {
            disabled.gateway("toss", PaymentConfirmGateway::class)
        }
    }

    @Test
    fun `resolve refuses a gateway that is not a full payment provider`() {
        val exception = assertFailsWith<PaymentCapabilityUnsupportedException> {
            router.resolve(provider = "lookup-only", amount = null, country = null)
        }

        assertEquals("PAYMENT.CAPABILITY_UNSUPPORTED", exception.errorCode.code)
    }

    @Test
    fun `gateways with the same provider id fail the router construction`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentProviderRouter(
                providers = listOf(LegacyProvider("toss"), LookupOnlyGateway(" TOSS ")),
                properties = PaymentProperties(),
            )
        }
    }

    @Test
    fun `snapshot carries card and refunds`() {
        val snapshot = PaymentSnapshot(
            providerPaymentId = "pay_1",
            orderId = "ord_1",
            status = ProviderPaymentStatus.PARTIALLY_REFUNDED,
            total = PaymentAmount(3_000, "KRW"),
            remaining = PaymentAmount(2_000, "KRW"),
            method = "CARD",
            paidAt = Instant.parse("2026-10-08T00:00:00Z"),
            refunds = listOf(ProviderRefund("rf_1", PaymentAmount(1_000, "KRW"), Instant.parse("2026-10-08T01:00:00Z"))),
            card = PaymentCard(issuer = "HYUNDAI", last4 = "12**"),
        )

        assertEquals("HYUNDAI", snapshot.card?.issuer)
        assertEquals(1, snapshot.refunds.size)
        assertNull(snapshot.copy(card = null).card)
    }

    @Test
    fun `card keeps a valid last four, drops anything else, and does not print it`() {
        assertEquals("12**", PaymentCard("HYUNDAI", "12**").last4)
        assertEquals("1234", PaymentCard("HYUNDAI", "1234").last4)
        assertEquals("34", PaymentCard("HYUNDAI", "34").last4)
        assertNull(PaymentCard("HYUNDAI", "12345").last4)
        assertNull(PaymentCard("HYUNDAI", "12ab").last4)
        assertNull(PaymentCard("HYUNDAI", "").last4)
        assertEquals(PaymentCard("HYUNDAI", "1234"), PaymentCard("HYUNDAI", "1234"))

        val printed = PaymentCard("HYUNDAI", "9876").toString()
        assertEquals(false, printed.contains("9876"), printed)
    }

    @Test
    fun `webhook envelope does not print the stored payload`() {
        val envelope = WebhookEnvelope("e1", "T", "payment", "pay_1", storedPayload = "SECRET-RAW-BODY")

        assertEquals(false, envelope.toString().contains("SECRET-RAW-BODY"), envelope.toString())
        assertEquals("SECRET-RAW-BODY", envelope.storedPayload)
    }

    @Test
    fun `webhook delivery lowercases header names`() {
        val delivery = WebhookDelivery(mapOf("TossPayments-Webhook-Transmission-Id" to "t-1"), ByteArray(0))

        assertEquals("t-1", delivery.headers["tosspayments-webhook-transmission-id"])
    }

    @Test
    fun `amounts compare by normalized currency with sameAs`() {
        assertEquals(true, PaymentAmount(1_000, " krw ").sameAs(PaymentAmount(1_000, "KRW")))
        assertEquals(false, PaymentAmount(1_000, "KRW").sameAs(PaymentAmount(999, "KRW")))
        assertEquals(false, PaymentAmount(1_000, "KRW").sameAs(PaymentAmount(1_000, "USD")))
    }

    @Test
    fun `exception details - the response text - do not name provider ids or internal interfaces`() {
        val unsupported = PaymentCapabilityUnsupportedException("secret-provider", "PaymentLookupGateway")
        val notFound = PaymentProviderNotFoundException("secret-provider")

        assertEquals(false, unsupported.detail.orEmpty().contains("secret-provider"))
        assertEquals(false, unsupported.detail.orEmpty().contains("Gateway"))
        assertEquals(false, notFound.detail.orEmpty().contains("secret-provider"))
    }

    @Test
    fun `a rejected webhook carries a fixed sentence only`() {
        val rejected = PaymentWebhookRejectedException()

        assertEquals("The payment webhook was rejected.", rejected.message)
    }

    private class LegacyProvider(override val providerId: String) : PaymentProvider {
        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult = error("not used")
        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult = error("not used")
        override fun refund(request: PaymentRefundRequest): PaymentOperationResult = error("not used")
    }

    private class LookupOnlyGateway(override val providerId: String) : PaymentLookupGateway {
        override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? = null
    }
}
