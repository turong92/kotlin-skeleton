package dev.sumin.skeleton.captcha.turnstile

import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(afterName = ["dev.sumin.skeleton.common.http.OutboundHttpAutoConfiguration"])
@ConditionalOnProperty(prefix = "skeleton.captcha-turnstile", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(ExternalHttpClient::class)
@EnableConfigurationProperties(TurnstileProperties::class)
class TurnstileAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun turnstileVerifier(http: ExternalHttpClient, properties: TurnstileProperties): TurnstileVerifier {
        require(properties.secretKey.isNotBlank()) { "skeleton.captcha-turnstile.secret-key must be set when enabled." }
        return TurnstileVerifier(http, properties)
    }
}
