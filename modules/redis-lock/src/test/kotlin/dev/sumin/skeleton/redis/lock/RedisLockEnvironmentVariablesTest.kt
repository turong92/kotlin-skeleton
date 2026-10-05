package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 배포 계약(docs/deploy.md): Redis 를 쓰지 않는 앱(`redis: false`)에는 플랫폼이 `<ENV_PREFIX>_REDIS_LOCK_ENABLED=false` 하나만 넣는다.
 * 그 이름이 `skeleton.redis-lock.enabled` 에 닿아야 락이 꺼지고 기동 점검(localhost:6379)도 돌지 않는다. 찍은 프로젝트에서는 접두사가 바뀐다.
 */
class RedisLockEnvironmentVariablesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisLockAutoConfiguration::class.java))

    @Test
    fun `the variable the platform injects for redis false turns the lock module off`() {
        contextRunner
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource("systemEnvironment", mapOf("SKELETON_REDIS_LOCK_ENABLED" to "false")),
                )
            }
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(RedisLockProperties::class.java)
                assertThat(context).doesNotHaveBean(RedisLockStartupChecker::class.java)
            }
    }
}
