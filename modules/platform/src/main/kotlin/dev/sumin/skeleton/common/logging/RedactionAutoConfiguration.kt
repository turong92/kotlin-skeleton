package dev.sumin.skeleton.common.logging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(RedactionProperties::class)
class RedactionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun sensitiveValueRedactor(properties: RedactionProperties): SensitiveValueRedactor =
        SensitiveValueRedactor(properties)
}
