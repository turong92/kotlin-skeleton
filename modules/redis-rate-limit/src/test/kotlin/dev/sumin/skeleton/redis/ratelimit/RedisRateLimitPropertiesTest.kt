package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisRateLimitPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisRateLimitAutoConfiguration::class.java))

    @Test
    fun `binds default redis rate limit properties`() {
        contextRunner.run { context ->
            val properties = context.getBean(RedisRateLimitProperties::class.java)

            assertThat(properties.enabled).isTrue()
            assertThat(properties.failurePolicy).isEqualTo(RedisRateLimitProperties.FailurePolicy.FAIL_OPEN)
            assertThat(properties.keyNamespace).isEqualTo("default")
        }
    }

    @Test
    fun `binds redis rate limit overrides`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis-rate-limit.enabled=false",
                "skeleton.redis-rate-limit.failure-policy=fail-closed",
                "skeleton.redis-rate-limit.key-namespace=api",
            )
            .run { context ->
                val properties = context.getBean(RedisRateLimitProperties::class.java)

                assertThat(properties.enabled).isFalse()
                assertThat(properties.failurePolicy).isEqualTo(RedisRateLimitProperties.FailurePolicy.FAIL_CLOSED)
                assertThat(properties.keyNamespace).isEqualTo("api")
            }
    }
}
