package dev.sumin.skeleton.common.logging

import org.springframework.beans.factory.SmartInitializingSingleton
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

    @Bean
    @ConditionalOnMissingBean
    fun logMasker(properties: RedactionProperties): LogMasker =
        LogMasker(properties.output.patterns, properties.replacement, properties.output.maskEmails)

    /** logback 변환기는 스프링보다 먼저 만들어지므로 정적 자리([LogMasker.current])에 설정된 가림막을 꽂는다 */
    @Bean
    fun logMaskerInstaller(masker: LogMasker): SmartInitializingSingleton = SmartInitializingSingleton { LogMasker.install(masker) }
}
