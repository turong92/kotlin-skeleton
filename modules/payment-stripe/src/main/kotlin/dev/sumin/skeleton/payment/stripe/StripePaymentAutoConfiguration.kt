package dev.sumin.skeleton.payment.stripe

import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(StripePaymentProperties::class)
class StripePaymentAutoConfiguration {
    @Bean("stripePaymentProvider")
    @ConditionalOnProperty(
        prefix = "skeleton.payment-stripe",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(name = ["stripePaymentProvider"])
    fun stripePaymentProvider(
        httpClient: ExternalHttpClient,
        properties: StripePaymentProperties,
    ): StripePaymentProvider =
        StripePaymentProvider(httpClient = httpClient, properties = properties)
}
