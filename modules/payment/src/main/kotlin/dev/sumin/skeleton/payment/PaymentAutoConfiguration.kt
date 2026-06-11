package dev.sumin.skeleton.payment

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(PaymentProperties::class)
class PaymentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun paymentProviderRouter(
        providers: List<PaymentProvider>,
        properties: PaymentProperties,
    ): PaymentProviderRouter =
        PaymentProviderRouter(providers = providers, properties = properties)

    @Bean
    @ConditionalOnMissingBean
    fun paymentService(router: PaymentProviderRouter): PaymentService =
        PaymentService(router)
}
