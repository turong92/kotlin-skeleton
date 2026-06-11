package dev.sumin.skeleton.redis.core

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(RedisCoreProperties::class)
class RedisCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun redisKeyPrefixer(properties: RedisCoreProperties): RedisKeyPrefixer =
        RedisKeyPrefixer(properties.keyPrefix)
}
