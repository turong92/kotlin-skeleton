package dev.sumin.skeleton.redis.cache

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CachingConfigurer
import org.springframework.cache.interceptor.CacheErrorHandler
import org.springframework.cache.interceptor.KeyGenerator
import org.springframework.cache.support.NoOpCacheManager
import org.springframework.data.redis.cache.RedisCacheManager

class RedisCacheAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisCacheAutoConfiguration::class.java))

    @Test
    fun `creates redis cache manager and registry when enabled`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis-cache.default-ttl=1m",
                "skeleton.redis-cache.caches.users.ttl=5m",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(RedisCacheProperties::class.java)
                assertThat(context).hasSingleBean(NamedRedisCacheRegistry::class.java)
                assertThat(context).hasSingleBean(CacheManager::class.java)
                assertThat(context.getBean(CacheManager::class.java)).isInstanceOf(RedisCacheManager::class.java)
                assertThat(context).hasBean("redisCacheKeyGenerator")
                assertThat(context.getBean("redisCacheKeyGenerator", KeyGenerator::class.java)).isInstanceOf(StableRedisCacheKeyGenerator::class.java)
                assertThat(context.getBean(CacheErrorHandler::class.java)).isInstanceOf(FailOpenRedisCacheErrorHandler::class.java)
                assertThat(context).hasSingleBean(CachingConfigurer::class.java)
                assertThat(context.getBean(CachingConfigurer::class.java).keyGenerator()).isInstanceOf(StableRedisCacheKeyGenerator::class.java)
                assertThat(context.getBean(CachingConfigurer::class.java).errorHandler()).isInstanceOf(FailOpenRedisCacheErrorHandler::class.java)

                val registry = context.getBean(NamedRedisCacheRegistry::class.java)
                assertThat(registry.defaultTtl).isEqualTo(Duration.ofMinutes(1))
                assertThat(registry.cacheTtl("users")).isEqualTo(Duration.ofMinutes(5))
                assertThat(registry.cacheTtl("missing")).isEqualTo(Duration.ofMinutes(1))
            }
    }

    @Test
    fun `creates no-op cache manager when fallback is enabled`() {
        contextRunner
            .withPropertyValues("skeleton.redis-cache.no-op-fallback.enabled=true")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(CacheManager::class.java)).isInstanceOf(NoOpCacheManager::class.java)
            }
    }

    @Test
    fun `does not create cache beans when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.redis-cache.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CacheManager::class.java)
                assertThat(context).doesNotHaveBean(NamedRedisCacheRegistry::class.java)
            }
    }
}
