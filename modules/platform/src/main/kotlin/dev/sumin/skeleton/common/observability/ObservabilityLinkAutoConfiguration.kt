package dev.sumin.skeleton.common.observability

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(ObservabilityLinkProperties::class)
class ObservabilityLinkAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObservabilityLinkResolver::class)
    fun observabilityLinkResolver(properties: ObservabilityLinkProperties): ObservabilityLinkResolver =
        DefaultObservabilityLinkResolver(properties)
}
