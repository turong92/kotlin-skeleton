package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** 능력만 구현한 게이트웨이(조회 전용 · 결제창 전용)가 있어도 기존 라우팅이 달라지지 않는다. */
class PaymentProviderRouterCapabilityTest {
    private val toss = FullProvider("toss")
    private val lookupOnly = LookupOnly("lookup-only")
    private val checkoutOnly = CheckoutOnly("checkout-only")

    private fun router(vararg gateways: PaymentGateway, properties: PaymentProperties = PaymentProperties()) =
        PaymentProviderRouter(gateways.toList(), properties)

    @Test
    fun `the single provider shortcut ignores gateways that are not payment providers`() {
        assertSame<Any>(toss, router(toss, lookupOnly).resolve(null, null, null))
    }

    @Test
    fun `a gateway with the same currency does not make the currency route ambiguous`() {
        val router = router(
            toss,
            lookupOnly,
            properties = PaymentProperties(
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(currencies = setOf("KRW")),
                    "lookup-only" to PaymentProperties.Provider(currencies = setOf("KRW")),
                ),
            ),
        )

        assertSame<Any>(toss, router.resolve(null, PaymentAmount(1_000, "KRW"), null))
    }

    @Test
    fun `a gateway with the same country does not make the country route ambiguous`() {
        val router = router(
            toss,
            lookupOnly,
            properties = PaymentProperties(
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(countries = setOf("KR")),
                    "lookup-only" to PaymentProperties.Provider(countries = setOf("KR")),
                ),
            ),
        )

        assertSame<Any>(toss, router.resolve(null, null, "KR"))
    }

    @Test
    fun `a currency only a non-provider gateway handles falls through to the default provider`() {
        val router = router(
            toss,
            lookupOnly,
            properties = PaymentProperties(
                defaultProvider = "toss",
                providers = mapOf("lookup-only" to PaymentProperties.Provider(currencies = setOf("JPY"))),
            ),
        )

        assertSame<Any>(toss, router.resolve(null, PaymentAmount(1_000, "JPY"), null))
    }

    @Test
    fun `an explicit id naming a gateway that is not a payment provider is still capability unsupported`() {
        val error = assertFailsWith<PaymentCapabilityUnsupportedException> {
            router(toss, lookupOnly).resolve("lookup-only", null, null)
        }

        assertEquals("PAYMENT.CAPABILITY_UNSUPPORTED", error.errorCode.code)
    }

    @Test
    fun `routing by capability picks a gateway without an id - single one`() {
        val picked = router(toss, checkoutOnly).resolve(null, null, null, PaymentCheckoutGateway::class)

        assertSame<Any>(checkoutOnly, picked)
    }

    @Test
    fun `routing by capability uses currency then country then default among capable gateways only`() {
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
    }

    @Test
    fun `a default provider that lacks the capability is skipped - the default step only searches the capable gateways`() {
        // toss 는 결제창 능력이 없다. 기본 결제사가 toss 여도 결제창 후보(둘) 중에서 못 고르면 "고를 수 없음"이지, 능력 없는 toss 를 골라 CAPABILITY_UNSUPPORTED 로 끝나면 안 된다
        val router = router(
            toss,
            checkoutOnly,
            CheckoutOnly("second"),
            properties = PaymentProperties(defaultProvider = "toss"),
        )

        val error = assertFailsWith<PaymentRoutingException> {
            router.resolve(null, null, null, PaymentCheckoutGateway::class)
        }

        assertEquals("PAYMENT.ROUTING_FAILED", error.errorCode.code)
    }

    @Test
    fun `a default provider that has the capability is still chosen among the capable gateways`() {
        val second = CheckoutOnly("second")
        val router = router(checkoutOnly, second, toss, properties = PaymentProperties(defaultProvider = "second"))

        assertSame<Any>(second, router.resolve(null, null, null, PaymentCheckoutGateway::class))
    }

    @Test
    fun `routing by capability with an explicit id checks the capability`() {
        val router = router(toss, checkoutOnly)

        assertSame<Any>(checkoutOnly, router.resolve(" Checkout-Only ", null, null, PaymentCheckoutGateway::class))
        assertFailsWith<PaymentCapabilityUnsupportedException> {
            router.resolve("toss", null, null, PaymentCheckoutGateway::class)
        }
        assertFailsWith<PaymentProviderNotFoundException> {
            router.resolve("nope", null, null, PaymentCheckoutGateway::class)
        }
    }

    @Test
    fun `routing by capability fails with a routing error when no capable gateway matches`() {
        val router = router(
            toss,
            checkoutOnly,
            CheckoutOnly("second"),
        )

        assertFailsWith<PaymentRoutingException> {
            router.resolve(null, null, null, PaymentCheckoutGateway::class)
        }
        assertFailsWith<PaymentRoutingException> {
            router(toss).resolve(null, null, null, PaymentCheckoutGateway::class)
        }
    }

    @Test
    fun `the single provider shortcut does not look at enabled - kept for compatibility, gateway does`() {
        val router = router(
            toss,
            properties = PaymentProperties(providers = mapOf("toss" to PaymentProperties.Provider(enabled = false))),
        )

        assertSame<Any>(toss, router.resolve(null, null, null))
        assertFailsWith<PaymentProviderNotFoundException> { router.gateway("toss", PaymentConfirmGateway::class) }
    }

    @Test
    fun `a blank provider id is not found`() {
        assertFailsWith<PaymentProviderNotFoundException> {
            router(toss).gateway("  ", PaymentConfirmGateway::class)
        }
    }

    private class FullProvider(override val providerId: String) : PaymentProvider {
        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult = error("not used")
        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult = error("not used")
        override fun refund(request: PaymentRefundRequest): PaymentOperationResult = error("not used")
    }

    private class LookupOnly(override val providerId: String) : PaymentLookupGateway {
        override fun lookup(query: PaymentLookupQuery): PaymentSnapshot? = null
    }

    private class CheckoutOnly(override val providerId: String) : PaymentCheckoutGateway {
        override fun checkout(order: CheckoutOrder): CheckoutInstruction = error("not used")
    }
}
