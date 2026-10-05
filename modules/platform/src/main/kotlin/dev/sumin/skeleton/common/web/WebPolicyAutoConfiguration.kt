package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.time.asClock
import java.time.Clock
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnMissingBean
    fun publicEndpointRegistry(
        properties: WebProperties,
        contributors: ObjectProvider<PublicEndpointContributor>,
    ): PublicEndpointRegistry =
        PublicEndpointRegistry.from(properties, contributors.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun clientIps(properties: WebProperties): ClientIps =
        ClientIps(properties.clientIp).also { log.info("Client IP: {}", it.describe()) }
            .also {
                if (!it.configured && properties.forwardedHeaders.enabled) {
                    log.warn(
                        "skeleton.web.client-ip.mode is not set: rate limit / idempotency keys use remoteAddr, and ForwardedHeaderFilter " +
                            "lets any caller choose it with X-Forwarded-For. Set mode=direct (no proxy) or proxy / cloudflare (behind one).",
                    )
                }
            }

    /** `ForwardedHeaderFilter` 가 `remoteAddr` 를 덮어쓰기 **전에** 클라이언트를 풀어 둔다 — mode 를 정했을 때만 */
    @Bean
    @ConditionalOnMissingBean(name = ["clientIpFilterRegistration"])
    @ConditionalOnProperty(prefix = "skeleton.web.client-ip", name = ["mode"])
    fun clientIpFilterRegistration(clientIps: ClientIps): FilterRegistrationBean<ClientIpFilter> =
        FilterRegistrationBean(ClientIpFilter(clientIps)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }

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
    fun rateLimitKeyResolver(clientIps: ClientIps): RateLimitKeyResolver =
        ClientIpRateLimitKeyResolver(clientIps)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.web.rate-limit", name = ["enabled"], havingValue = "true")
    fun rateLimitStore(timeProvider: ObjectProvider<TimeProvider>): RateLimitStore =
        InMemoryFixedWindowRateLimitStore(timeProvider.getIfAvailable()?.asClock() ?: Clock.systemUTC())

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
