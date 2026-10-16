package dev.sumin.skeleton.payment.stripe

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectsConfigured
import dev.sumin.skeleton.common.project.ProjectsNotConfigured
import dev.sumin.skeleton.payment.PaymentGatewaySource
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional

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
    @Conditional(ProjectsNotConfigured::class)
    fun stripePaymentProvider(
        httpClient: ExternalHttpClient,
        properties: StripePaymentProperties,
    ): StripePaymentProvider =
        StripePaymentProvider(httpClient = httpClient, properties = properties)

    /** 프로젝트 모드: 프로젝트마다 자기 `payment-stripe.*` 로 만든다. 그 프로젝트에서 켜지 않았으면 없다. */
    @Bean
    @ConditionalOnMissingBean(name = ["stripeGatewaySource"])
    @Conditional(ProjectsConfigured::class)
    fun stripeGatewaySource(httpClient: ExternalHttpClient): PaymentGatewaySource =
        PaymentGatewaySource.of(listOf("payment-stripe.secret-key")) { settings ->
            val properties = settings.bind("skeleton.payment-stripe", StripePaymentProperties::class.java)
            if (properties.enabled) StripePaymentProvider(httpClient = httpClient, properties = properties) else null
        }
}
