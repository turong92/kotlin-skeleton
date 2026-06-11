package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import java.util.function.Supplier
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisLockPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisLockAutoConfiguration::class.java))
        .withBean(RedissonClient::class.java, Supplier { RedissonClientProxy.create() })
        .withBean(RedisLockBackendVerifier::class.java, Supplier { RedisLockBackendVerifier { } })

    @Test
    fun `binds default redis lock properties`() {
        contextRunner.run { context ->
            val properties = context.getBean(RedisLockProperties::class.java)

            assertThat(properties.enabled).isTrue()
            assertThat(properties.startupCheck.enabled).isTrue()
            assertThat(properties.startupCheck.key).isEqualTo("__redis_lock_startup_check")
            assertThat(properties.retry.attempts).isEqualTo(3)
            assertThat(properties.retry.backoff).isEqualTo(Duration.ofMillis(200))
            assertThat(properties.redisson.retryAttempts).isEqualTo(3)
            assertThat(properties.redisson.retryInterval).isEqualTo(Duration.ofMillis(1500))
            assertThat(properties.redisson.timeout).isEqualTo(Duration.ofSeconds(3))
        }
    }

    @Test
    fun `binds redis lock overrides`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis-lock.startup-check.enabled=false",
                "skeleton.redis-lock.startup-check.key=custom-health",
                "skeleton.redis-lock.retry.attempts=5",
                "skeleton.redis-lock.retry.backoff=75ms",
                "skeleton.redis-lock.redisson.retry-attempts=2",
                "skeleton.redis-lock.redisson.retry-interval=300ms",
                "skeleton.redis-lock.redisson.timeout=900ms",
            )
            .run { context ->
                val properties = context.getBean(RedisLockProperties::class.java)

                assertThat(properties.startupCheck.enabled).isFalse()
                assertThat(properties.startupCheck.key).isEqualTo("custom-health")
                assertThat(properties.retry.attempts).isEqualTo(5)
                assertThat(properties.retry.backoff).isEqualTo(Duration.ofMillis(75))
                assertThat(properties.redisson.retryAttempts).isEqualTo(2)
                assertThat(properties.redisson.retryInterval).isEqualTo(Duration.ofMillis(300))
                assertThat(properties.redisson.timeout).isEqualTo(Duration.ofMillis(900))
            }
    }
}
