package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(TossPaymentProperties::class)
class TossPaymentAutoConfiguration {
    @Bean("tossPaymentProvider")
    @ConditionalOnProperty(
        prefix = "skeleton.payment-toss",
        name = ["enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(name = ["tossPaymentProvider"])
    fun tossPaymentProvider(
        httpClient: ExternalHttpClient,
        properties: TossPaymentProperties,
    ): TossPaymentProvider =
        TossPaymentProvider(httpClient = httpClient, properties = properties)
}
