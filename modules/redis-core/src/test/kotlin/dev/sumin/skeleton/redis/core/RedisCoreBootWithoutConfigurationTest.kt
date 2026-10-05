package dev.sumin.skeleton.redis.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 모듈만 얹고 설정이 하나도 없어도 뜬다 — Redis 가 없어도 (Lettuce 는 첫 명령 때 연결한다). docs/minimal-composition.md */
class RedisCoreBootWithoutConfigurationTest {
    @Test
    fun `boots with no configuration and no Redis server`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java))
            .run { context -> assertThat(context).hasNotFailed() }
    }
}
