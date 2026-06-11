package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.common.web.RateLimitKeyResolver
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisRateLimitAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisRateLimitAutoConfiguration::class.java))

    @Test
    fun `creates redis rate limit store and principal-aware key resolver when enabled`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(RedisRateLimitProperties::class.java)
            assertThat(context).hasSingleBean(RedisFixedWindowCounter::class.java)
            assertThat(context).hasSingleBean(RateLimitStore::class.java)
            assertThat(context.getBean(RateLimitStore::class.java)).isInstanceOf(RedisFixedWindowRateLimitStore::class.java)
            assertThat(context.getBean(RateLimitKeyResolver::class.java)).isInstanceOf(PrincipalAwareRateLimitKeyResolver::class.java)
        }
    }

    @Test
    fun `does not create rate limit beans when disabled`() {
        contextRunner
            .withPropertyValues("skeleton.redis-rate-limit.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(RateLimitStore::class.java)
                assertThat(context).doesNotHaveBean(RedisFixedWindowCounter::class.java)
            }
    }
}
