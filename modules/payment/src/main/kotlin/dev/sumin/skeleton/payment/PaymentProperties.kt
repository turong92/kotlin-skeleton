package dev.sumin.skeleton.payment

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.payment")
data class PaymentProperties(
    val defaultProvider: String? = null,
    val providers: Map<String, Provider> = emptyMap(),
) {
    data class Provider(
        val enabled: Boolean = true,
        val currencies: Set<String> = emptySet(),
        val countries: Set<String> = emptySet(),
    )
}
