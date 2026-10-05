package dev.sumin.skeleton.redis.cache

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.cache.CacheManager

/** 모듈 + 선언된 의존(redis-core)만 얹고 설정이 없으면 뜬다 — Redis 가 없어도 (연결은 첫 캐시 접근 때, 실패는 FAIL_OPEN). */
class RedisCacheBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration and no Redis server`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java, RedisCacheAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(CacheManager::class.java)
            }
    }
}
