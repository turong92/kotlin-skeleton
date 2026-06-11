package dev.sumin.skeleton.payment.toss

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.payment-toss")
data class TossPaymentProperties(
    val enabled: Boolean = false,
    val providerId: String = "toss",
    val clientName: String = "payment-toss",
    val secretKey: String = "",
    val baseUrl: String = "https://api.tosspayments.com",
    val confirmPath: String = "/v1/payments/confirm",
    val cancelPath: String = "/v1/payments/{paymentKey}/cancel",
    val supportedCurrencies: Set<String> = setOf("KRW"),
    val supportedCountries: Set<String> = setOf("KR"),
)
