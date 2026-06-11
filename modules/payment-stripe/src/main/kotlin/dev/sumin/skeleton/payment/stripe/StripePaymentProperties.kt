package dev.sumin.skeleton.payment.stripe

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.payment-stripe")
data class StripePaymentProperties(
    val enabled: Boolean = false,
    val providerId: String = "stripe",
    val clientName: String = "payment-stripe",
    val secretKey: String = "",
    val baseUrl: String = "https://api.stripe.com",
    val confirmPath: String = "/v1/payment_intents/{paymentIntentId}/confirm",
    val cancelPath: String = "/v1/payment_intents/{paymentIntentId}/cancel",
    val refundPath: String = "/v1/refunds",
    val supportedCurrencies: Set<String> = emptySet(),
    val supportedCountries: Set<String> = emptySet(),
)
