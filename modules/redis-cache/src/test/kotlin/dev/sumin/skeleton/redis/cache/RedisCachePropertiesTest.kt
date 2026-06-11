package dev.sumin.skeleton.redis.cache

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisCachePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisCacheAutoConfiguration::class.java))

    @Test
    fun `binds default redis cache properties`() {
        contextRunner.run { context ->
            val properties = context.getBean(RedisCacheProperties::class.java)

            assertThat(properties.enabled).isTrue()
            assertThat(properties.defaultTtl).isEqualTo(Duration.ofMinutes(10))
            assertThat(properties.failurePolicy).isEqualTo(RedisCacheProperties.FailurePolicy.FAIL_OPEN)
            assertThat(properties.noOpFallback.enabled).isFalse()
            assertThat(properties.caches).isEmpty()
        }
    }

    @Test
    fun `binds named cache ttl overrides`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis-cache.default-ttl=45s",
                "skeleton.redis-cache.failure-policy=fail-closed",
                "skeleton.redis-cache.no-op-fallback.enabled=true",
                "skeleton.redis-cache.caches.users.ttl=5m",
                "skeleton.redis-cache.caches.tokens.ttl=30s",
            )
            .run { context ->
                val properties = context.getBean(RedisCacheProperties::class.java)

                assertThat(properties.defaultTtl).isEqualTo(Duration.ofSeconds(45))
                assertThat(properties.failurePolicy).isEqualTo(RedisCacheProperties.FailurePolicy.FAIL_CLOSED)
                assertThat(properties.noOpFallback.enabled).isTrue()
                assertThat(properties.caches).containsOnlyKeys("users", "tokens")
                assertThat(properties.caches.getValue("users").ttl).isEqualTo(Duration.ofMinutes(5))
                assertThat(properties.caches.getValue("tokens").ttl).isEqualTo(Duration.ofSeconds(30))
            }
    }
}
