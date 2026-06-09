package dev.sumin.skeleton.common.time

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
class TimeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun timeProvider(): TimeProvider =
        TimeProvider.systemUtc()
}
