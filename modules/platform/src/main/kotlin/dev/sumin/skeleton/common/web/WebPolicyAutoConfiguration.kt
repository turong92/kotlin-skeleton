package dev.sumin.skeleton.common.web

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import org.springframework.web.filter.ForwardedHeaderFilter
import tools.jackson.databind.ObjectMapper

@AutoConfiguration
@EnableConfigurationProperties(WebProperties::class)
class WebPolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun publicEndpointRegistry(
        properties: WebProperties,
        contributors: ObjectProvider<PublicEndpointContributor>,
    ): PublicEndpointRegistry =
        PublicEndpointRegistry.from(properties, contributors.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean(name = ["forwardedHeaderFilterRegistration"])
    @ConditionalOnProperty(
        prefix = "skeleton.web.forwarded-headers",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun forwardedHeaderFilterRegistration(): FilterRegistrationBean<ForwardedHeaderFilter> =
        FilterRegistrationBean(ForwardedHeaderFilter()).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 1
        }

    @Bean
    @ConditionalOnMissingBean(name = ["securityHeadersFilterRegistration"])
    @ConditionalOnProperty(
        prefix = "skeleton.web.security-headers",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun securityHeadersFilterRegistration(properties: WebProperties): FilterRegistrationBean<SecurityHeadersFilter> =
        FilterRegistrationBean(SecurityHeadersFilter(properties.securityHeaders)).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 20
    }

    @Bean("skeletonCorsConfigurationSource")
    @ConditionalOnMissingBean(name = ["skeletonCorsConfigurationSource"])
    @ConditionalOnProperty(prefix = "skeleton.web.cors", name = ["enabled"], havingValue = "true")
    fun corsConfigurationSource(properties: WebProperties): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            allowedOriginPatterns = properties.cors.allowedOriginPatterns
            allowedMethods = properties.cors.allowedMethods
            allowedHeaders = properties.cors.allowedHeaders
            exposedHeaders = properties.cors.exposedHeaders
            allowCredentials = properties.cors.allowCredentials
            maxAge = properties.cors.maxAge.seconds
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration(properties.cors.pathPattern, configuration)
        }
    }

    @Bean
    @ConditionalOnMissingBean(name = ["corsFilterRegistration"])
    @ConditionalOnProperty(prefix = "skeleton.web.cors", name = ["enabled"], havingValue = "true")
    fun corsFilterRegistration(
        @Qualifier("skeletonCorsConfigurationSource") corsConfigurationSource: CorsConfigurationSource,
    ): FilterRegistrationBean<CorsFilter> =
        FilterRegistrationBean(CorsFilter(corsConfigurationSource)).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 30
        }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.web.rate-limit", name = ["enabled"], havingValue = "true")
    fun rateLimitKeyResolver(): RateLimitKeyResolver =
        ClientIpRateLimitKeyResolver()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.web.rate-limit", name = ["enabled"], havingValue = "true")
    fun rateLimitStore(): RateLimitStore =
        InMemoryFixedWindowRateLimitStore()

    @Bean
    @ConditionalOnMissingBean(name = ["rateLimitFilterRegistration"])
    @ConditionalOnProperty(prefix = "skeleton.web.rate-limit", name = ["enabled"], havingValue = "true")
    fun rateLimitFilterRegistration(
        properties: WebProperties,
        keyResolver: RateLimitKeyResolver,
        store: RateLimitStore,
        objectMapper: ObjectMapper,
    ): FilterRegistrationBean<RateLimitFilter> =
        FilterRegistrationBean(RateLimitFilter(properties.rateLimit, keyResolver, store, objectMapper)).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 40
        }
}
