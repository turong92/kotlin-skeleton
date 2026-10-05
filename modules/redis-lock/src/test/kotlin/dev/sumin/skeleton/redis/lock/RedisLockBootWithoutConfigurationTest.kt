package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * 모듈 + 선언된 의존(redis-core)만 얹고 설정이 없으면 뜬다 — Redis 가 없어도.
 * 락은 꺼 두지 않는다(꺼 두면 @DistributedLock 이 조용히 아무 일도 안 한다): Redis 연결은 첫 락 사용 때 하고,
 * 기동 때 Redis 를 찔러 보는 시작 점검은 `skeleton.redis-lock.startup-check.enabled=true` 로 켠다.
 */
class RedisLockBootWithoutConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisLockAutoConfiguration::class.java))

    @Test
    fun `boots with no configuration and no Redis server`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DistributedLockExecutor::class.java)
            assertThat(context).hasSingleBean(DistributedLockAspect::class.java)
        }
    }

    @Test
    fun `the startup check is opt in and fails the boot when Redis is unreachable`() {
        runner
            .withPropertyValues(
                "skeleton.redis.port=1",
                "skeleton.redis-lock.startup-check.enabled=true",
                "skeleton.redis-lock.redisson.retry-attempts=0",
                "skeleton.redis-lock.redisson.timeout=300ms",
            )
            .run { context -> assertThat(context).hasFailed() }
    }
}
