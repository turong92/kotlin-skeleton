package dev.sumin.skeleton.redis.cache

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.interceptor.SimpleCacheErrorHandler
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.cache.RedisCacheManager
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@AutoConfiguration(after = [RedisCoreAutoConfiguration::class])
@EnableConfigurationProperties(RedisCacheProperties::class)
class RedisCacheAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-cache",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun namedRedisCacheRegistry(properties: RedisCacheProperties): NamedRedisCacheRegistry =
        NamedRedisCacheRegistry.from(properties)

    @Bean("redisCacheKeyGenerator")
    @ConditionalOnMissingBean(name = ["redisCacheKeyGenerator"])
    @ConditionalOnProperty(
        prefix = "skeleton.redis-cache",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun redisCacheKeyGenerator(): KeyGenerator =
        StableRedisCacheKeyGenerator()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-cache",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun redisCacheErrorHandler(properties: RedisCacheProperties): CacheErrorHandler =
        when (properties.failurePolicy) {
            RedisCacheProperties.FailurePolicy.FAIL_OPEN -> FailOpenRedisCacheErrorHandler()
            RedisCacheProperties.FailurePolicy.FAIL_CLOSED -> SimpleCacheErrorHandler()
        }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-cache",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun redisCacheCachingConfigurer(
        @Qualifier("redisCacheKeyGenerator") keyGenerator: KeyGenerator,
        errorHandler: CacheErrorHandler,
    ): CachingConfigurer =
        RedisCacheCachingConfigurer(
            keyGenerator = keyGenerator,
            errorHandler = errorHandler,
        )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "skeleton.redis-cache",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun cacheManager(
        properties: RedisCacheProperties,
        registry: NamedRedisCacheRegistry,
        redisConnectionFactoryProvider: ObjectProvider<RedisConnectionFactory>,
        redisKeyPrefixer: RedisKeyPrefixer,
        @Qualifier("redisStringSerializer") stringSerializerProvider: ObjectProvider<RedisSerializer<String>>,
        @Qualifier("redisJsonSerializer") jsonSerializerProvider: ObjectProvider<RedisSerializer<Any>>,
    ): CacheManager {
        if (properties.noOpFallback.enabled) {
            return NoOpCacheManager()
        }

        val redisConnectionFactory = redisConnectionFactoryProvider.ifAvailable
            ?: return missingRedisFallback(properties, "RedisConnectionFactory")
        val stringSerializer = stringSerializerProvider.ifAvailable ?: StringRedisSerializer()
        val jsonSerializer = jsonSerializerProvider.ifAvailable ?: RedisSerializer.json()

        return RedisCacheManager.builder(redisConnectionFactory)
            .cacheDefaults(cacheConfiguration(registry.defaultTtl, redisKeyPrefixer, stringSerializer, jsonSerializer))
            .withInitialCacheConfigurations(
                registry.ttlByCacheName().mapValues { (_, ttl) ->
                    cacheConfiguration(ttl, redisKeyPrefixer, stringSerializer, jsonSerializer)
                },
            )
            .build()
    }

    private fun missingRedisFallback(
        properties: RedisCacheProperties,
        beanName: String,
    ): CacheManager =
        when (properties.failurePolicy) {
            RedisCacheProperties.FailurePolicy.FAIL_OPEN -> NoOpCacheManager()
            RedisCacheProperties.FailurePolicy.FAIL_CLOSED -> throw IllegalStateException("$beanName is required for redis cache")
        }

    private fun cacheConfiguration(
        ttl: java.time.Duration,
        redisKeyPrefixer: RedisKeyPrefixer,
        stringSerializer: RedisSerializer<String>,
        jsonSerializer: RedisSerializer<Any>,
    ): RedisCacheConfiguration =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)
            .disableCachingNullValues()
            .computePrefixWith { cacheName -> "${redisKeyPrefixer.key("cache", cacheName)}::" }
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(stringSerializer))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
}

private class RedisCacheCachingConfigurer(
    private val keyGenerator: KeyGenerator,
    private val errorHandler: CacheErrorHandler,
) : CachingConfigurer {
    override fun keyGenerator(): KeyGenerator =
        keyGenerator

    override fun errorHandler(): CacheErrorHandler =
        errorHandler
}
