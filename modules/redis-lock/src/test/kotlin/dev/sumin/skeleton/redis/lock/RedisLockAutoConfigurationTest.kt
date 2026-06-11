package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.redisson.api.RedissonClient
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisLockAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisLockAutoConfiguration::class.java))

    @Test
    fun `creates redis lock beans when startup check passes`() {
        contextRunner
            .withBean(RedissonClient::class.java, Supplier { RedissonClientProxy.create() })
            .withBean(RedisLockBackendVerifier::class.java, Supplier { RedisLockBackendVerifier { } })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(RedisLockProperties::class.java)
                assertThat(context).hasSingleBean(RedissonClient::class.java)
                assertThat(context).hasSingleBean(DistributedLockBackend::class.java)
                assertThat(context).hasSingleBean(DistributedLockExecutor::class.java)
                assertThat(context).hasSingleBean(DistributedLockKeyResolver::class.java)
                assertThat(context).hasSingleBean(DistributedLockAspect::class.java)
            }
    }

    @Test
    fun `does not create beans when redis lock is disabled`() {
        contextRunner
            .withPropertyValues("skeleton.redis-lock.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DistributedLockExecutor::class.java)
                assertThat(context).doesNotHaveBean(DistributedLockAspect::class.java)
            }
    }

    @Test
    fun `fails startup when startup check fails`() {
        contextRunner
            .withBean(
                RedisLockBackendVerifier::class.java,
                Supplier { RedisLockBackendVerifier { throw RedisLockBackendException("redis unavailable") } },
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).isInstanceOf(RedisLockBackendException::class.java)
            }
    }

    @Test
    fun `backs off when user provides redisson client`() {
        val userClient = RedissonClientProxy.create()

        contextRunner
            .withPropertyValues("skeleton.redis-lock.startup-check.enabled=false")
            .withBean(RedissonClient::class.java, Supplier { userClient })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(RedissonClient::class.java)).isSameAs(userClient)
            }
    }
}
