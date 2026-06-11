package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.common.web.RateLimitKeyResolver
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.common.web.WebPolicyAutoConfiguration
import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.core.StringRedisTemplate

@AutoConfiguration(
    after = [RedisCoreAutoConfiguration::class],
    before = [WebPolicyAutoConfiguration::class],
)
@EnableConfigurationProperties(RedisRateLimitProperties::class)
class RedisRateLimitAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-rate-limit",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun redisFixedWindowCounter(stringRedisTemplate: StringRedisTemplate): RedisFixedWindowCounter =
        StringRedisFixedWindowCounter(stringRedisTemplate)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-rate-limit",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun rateLimitStore(
        counter: RedisFixedWindowCounter,
        redisKeyPrefixer: RedisKeyPrefixer,
        properties: RedisRateLimitProperties,
    ): RateLimitStore =
        RedisFixedWindowRateLimitStore(
            counter = counter,
            redisKeyPrefixer = redisKeyPrefixer,
            properties = properties,
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-rate-limit",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun rateLimitKeyResolver(): RateLimitKeyResolver =
        PrincipalAwareRateLimitKeyResolver()
}
