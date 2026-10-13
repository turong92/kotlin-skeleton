package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class PaymentProviderRouterTest {
    private val toss = FakePaymentProvider("toss")
    private val lookupOnly = LookupOnly("lookup-only")
    private val checkoutOnly = CheckoutOnly("checkout-only")

    private fun router(vararg gateways: PaymentGateway, properties: PaymentProperties = PaymentProperties()) =
        PaymentProviderRouter(gateways.toList(), properties)

    @Test
    fun `explicit provider wins, then routing goes currency, country, default`() {
        val stripe = FakePaymentProvider("stripe")
        val router = router(
            toss,
            stripe,
            properties = PaymentProperties(
                defaultProvider = "stripe",
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                    "stripe" to PaymentProperties.Provider(currencies = setOf("USD"), countries = setOf("US")),
                ),
            ),
        )

        assertSame<Any>(stripe, router.resolve(" Stripe ", PaymentAmount(10_000, "KRW"), "KR"), "explicit")
        assertSame<Any>(toss, router.resolve(null, PaymentAmount(10_000, "krw"), "US"), "currency first")
        assertSame<Any>(toss, router.resolve(null, PaymentAmount(10_000, "JPY"), " kr "), "country when no currency route matches")
        assertSame<Any>(stripe, router.resolve(null, PaymentAmount(10_000, "JPY"), "JP"), "default when nothing matches")
    }

    @Test
    fun `gateways lists the enabled gateways with a capability, in id order`() {
        val adyen = FakePaymentProvider("adyen")
        val router = router(
            toss, lookupOnly, adyen, FakePaymentProvider("stripe"),
            properties = PaymentProperties(providers = mapOf("stripe" to PaymentProperties.Provider(enabled = false))),
        )
        assertEquals(listOf<Any>(adyen, toss), router.gateways(PaymentProvider::class))
    }

    @Test
    fun `disabled providers are not selected`() {
        val router = router(
            toss,
            properties = PaymentProperties(defaultProvider = "toss", providers = mapOf("toss" to PaymentProperties.Provider(enabled = false))),
        )

        assertFailsWith<PaymentProviderNotFoundException> { router.resolve("toss", null, null) }
        assertFailsWith<PaymentProviderNotFoundException> { router.gateway("toss", PaymentConfirmGateway::class) }
    }

    @Test
    fun `gateways with the same provider id fail the router construction`() {
        assertFailsWith<IllegalArgumentException> { router(FakePaymentProvider("stripe"), LookupOnly(" Stripe ")) }
    }

    @Test
    fun `gateways that are not payment providers do not take part in provider routing and an explicit id naming one is capability unsupported`() {
        assertSame<Any>(toss, router(toss, lookupOnly).resolve(null, null, null), "single provider shortcut")
        val sameRoutes = router(
            toss,
            lookupOnly,
            properties = PaymentProperties(
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                    "lookup-only" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                ),
            ),
        )
        assertSame<Any>(toss, sameRoutes.resolve(null, PaymentAmount(1_000, "KRW"), null), "currency not ambiguous")
        assertSame<Any>(toss, sameRoutes.resolve(null, null, "KR"), "country not ambiguous")
        assertFailsWith<PaymentCapabilityUnsupportedException> { router(toss, lookupOnly).resolve("lookup-only", null, null) }
    }

    @Test
    fun `routing by capability uses currency, country, then the default among capable gateways only - a default without the capability is not picked`() {
        val other = CheckoutOnly("other-checkout")
        val router = router(
            checkoutOnly,
            other,
            toss,
            properties = PaymentProperties(
                defaultProvider = "other-checkout",
                providers = mapOf(
                    "checkout-only" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                    "other-checkout" to PaymentProperties.Provider(currencies = setOf("USD"), countries = setOf("US")),
                    "toss" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                ),
            ),
        )

        assertSame<Any>(checkoutOnly, router.resolve(null, PaymentAmount(1, "krw"), null, PaymentCheckoutGateway::class))
        assertSame<Any>(other, router.resolve(null, null, "us", PaymentCheckoutGateway::class))
        assertSame<Any>(other, router.resolve(null, PaymentAmount(1, "JPY"), "JP", PaymentCheckoutGateway::class))
        val tossDefault = router(toss, checkoutOnly, other, properties = PaymentProperties(defaultProvider = "toss"))
        assertEquals("PAYMENT.ROUTING_FAILED", assertFailsWith<PaymentRoutingException> { tossDefault.resolve(null, null, null, PaymentCheckoutGateway::class) }.errorCode.code)
    }

    @Test
    fun `gateway checks the capability and the id - lookup only and checkout only gateways, unknown and blank ids`() {
        val router = router(toss, lookupOnly, checkoutOnly)

        assertSame<Any>(checkoutOnly, router.resolve(" Checkout-Only ", null, null, PaymentCheckoutGateway::class))
        assertFailsWith<PaymentCapabilityUnsupportedException> { router.resolve("toss", null, null, PaymentCheckoutGateway::class) }
        assertFailsWith<PaymentProviderNotFoundException> { router.resolve("nope", null, null, PaymentCheckoutGateway::class) }

        assertSame<Any>(router.gateway("toss", PaymentConfirmGateway::class), router.gateway(" Toss ", PaymentRefundGateway::class))
        assertNull(router.gateway("lookup-only", PaymentLookupGateway::class).lookup(PaymentLookupQuery(providerPaymentId = "pay_1", orderId = null)))
        assertFailsWith<PaymentCapabilityUnsupportedException> { router.gateway("toss", PaymentLookupGateway::class) }
        assertFailsWith<PaymentCapabilityUnsupportedException> { router.gateway("lookup-only", PaymentConfirmGateway::class) }
        assertFailsWith<PaymentProviderNotFoundException> { router.gateway("nope", PaymentConfirmGateway::class) }
        assertFailsWith<PaymentProviderNotFoundException> { router.gateway("  ", PaymentConfirmGateway::class) }
    }

    private class LookupOnly(override val providerId: String) : PaymentLookupGateway {
        override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? = null
    }

    private class CheckoutOnly(override val providerId: String) : PaymentCheckoutGateway {
        override fun checkout(order: CheckoutOrder): CheckoutInstruction = error("not used")
    }

    private class FakePaymentProvider(override val providerId: String) : PaymentProvider {
        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult = error("not used")
        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult = error("not used")
        override fun refund(request: PaymentRefundRequest): PaymentOperationResult = error("not used")
    }
}
