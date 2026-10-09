package dev.sumin.skeleton.common.logging

import org.springframework.beans.factory.ObjectProvider
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
    fun logMasker(properties: RedactionProperties, contributors: ObjectProvider<LogMaskingContributor>): LogMasker {
        val contributed = contributors.orderedStream().toList().flatMap { it.patterns() }
        // 기여자의 규칙은 logback 줄(설정 규칙 뒤)과 요청 로그의 경로(그 규칙만) 양쪽에 닿는다
        return LogMasker(properties.output.patterns + contributed, properties.replacement, properties.output.maskEmails, pathPatterns = contributed)
    }

    /** logback 변환기는 스프링보다 먼저 만들어지므로 정적 자리([LogMasker.current])에 설정된 가림막을 꽂는다 */
    @Bean
    fun logMaskerInstaller(masker: LogMasker): SmartInitializingSingleton = SmartInitializingSingleton { LogMasker.install(masker) }
}
