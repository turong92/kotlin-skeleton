package dev.sumin.skeleton.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaymentProviderRouterTest {
    @Test
    fun `explicit provider wins over currency and country routing`() {
        val toss = FakePaymentProvider("toss")
        val stripe = FakePaymentProvider("stripe")
        val router = PaymentProviderRouter(
            providers = listOf(toss, stripe),
            properties = PaymentProperties(
                defaultProvider = "stripe",
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                    "stripe" to PaymentProperties.Provider(currencies = setOf("USD"), countries = setOf("US")),
                ),
            ),
        )

        val selected = router.resolve(
            provider = " Stripe ",
            amount = PaymentAmount(amount = 10_000, currency = "KRW"),
            country = "KR",
        )

        assertEquals(stripe, selected)
    }

    @Test
    fun `currency route is used before country and default provider`() {
        val toss = FakePaymentProvider("toss")
        val stripe = FakePaymentProvider("stripe")
        val router = PaymentProviderRouter(
            providers = listOf(toss, stripe),
            properties = PaymentProperties(
                defaultProvider = "stripe",
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(currencies = setOf("KRW"), countries = setOf("KR")),
                    "stripe" to PaymentProperties.Provider(currencies = setOf("USD"), countries = setOf("KR")),
                ),
            ),
        )

        val selected = router.resolve(
            provider = null,
            amount = PaymentAmount(amount = 10_000, currency = "krw"),
            country = "KR",
        )

        assertEquals(toss, selected)
    }

    @Test
    fun `country route is used when no currency route matches`() {
        val toss = FakePaymentProvider("toss")
        val stripe = FakePaymentProvider("stripe")
        val router = PaymentProviderRouter(
            providers = listOf(toss, stripe),
            properties = PaymentProperties(
                defaultProvider = "stripe",
                providers = mapOf(
                    "toss" to PaymentProperties.Provider(countries = setOf("KR")),
                    "stripe" to PaymentProperties.Provider(countries = setOf("US")),
                ),
            ),
        )

        val selected = router.resolve(
            provider = null,
            amount = PaymentAmount(amount = 10_000, currency = "JPY"),
            country = " kr ",
        )

        assertEquals(toss, selected)
    }

    @Test
    fun `default provider is used when explicit currency and country routes do not match`() {
        val toss = FakePaymentProvider("toss")
        val stripe = FakePaymentProvider("stripe")
        val router = PaymentProviderRouter(
            providers = listOf(toss, stripe),
            properties = PaymentProperties(defaultProvider = "stripe"),
        )

        val selected = router.resolve(
            provider = null,
            amount = PaymentAmount(amount = 10_000, currency = "JPY"),
            country = "JP",
        )

        assertEquals(stripe, selected)
    }

    @Test
    fun `disabled providers are not selected`() {
        val router = PaymentProviderRouter(
            providers = listOf(FakePaymentProvider("toss")),
            properties = PaymentProperties(
                defaultProvider = "toss",
                providers = mapOf("toss" to PaymentProperties.Provider(enabled = false)),
            ),
        )

        assertFailsWith<PaymentProviderNotFoundException> {
            router.resolve(provider = "toss", amount = null, country = null)
        }
    }

    @Test
    fun `constructor rejects duplicate provider ids`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentProviderRouter(
                providers = listOf(FakePaymentProvider("stripe"), FakePaymentProvider(" Stripe ")),
                properties = PaymentProperties(),
            )
        }
    }

    private class FakePaymentProvider(
        override val providerId: String,
    ) : PaymentProvider {
        override fun confirm(request: PaymentConfirmRequest): PaymentOperationResult =
            PaymentOperationResult(
                provider = providerId.trim().lowercase(),
                providerPaymentId = request.providerPaymentId,
                status = PaymentOperationStatus.CONFIRMED,
                amount = request.amount,
                trace = ProviderTrace(provider = providerId.trim().lowercase()),
            )

        override fun cancel(request: PaymentCancelRequest): PaymentOperationResult =
            PaymentOperationResult(
                provider = providerId.trim().lowercase(),
                providerPaymentId = request.providerPaymentId,
                status = PaymentOperationStatus.CANCELED,
                amount = request.amount,
                trace = ProviderTrace(provider = providerId.trim().lowercase()),
            )

        override fun refund(request: PaymentRefundRequest): PaymentOperationResult =
            PaymentOperationResult(
                provider = providerId.trim().lowercase(),
                providerPaymentId = request.providerPaymentId,
                status = PaymentOperationStatus.REFUNDED,
                amount = request.amount,
                trace = ProviderTrace(provider = providerId.trim().lowercase()),
            )
    }
}
