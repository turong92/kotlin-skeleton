package dev.sumin.skeleton.redis.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 배포 계약(docs/deploy.md): 플랫폼은 Redis 를 `<ENV_PREFIX>_REDIS_HOST / _PORT / _SSL_ENABLED / _KEY_PREFIX / _PASSWORD` 로 넣는다.
 * 이 파일은 스켈레톤에서는 `SKELETON_REDIS_*` 이고, 새 프로젝트로 찍으면 rename 이 프로젝트 자기 접두사(`OVATION_REDIS_*`)로 바꾼다 —
 * 찍은 프로젝트의 빌드가 같은 테스트를 그대로 돌리므로 "접두사가 따라간다" 가 거기서 증명된다.
 * 진짜 `systemEnvironment` 속성 원본 모양으로 넣는다 (점 표기 속성으로 넣으면 환경변수 바인딩을 시험하지 못한다).
 */
class RedisEnvironmentVariablesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java))

    private fun env(vararg pairs: Pair<String, String>) = contextRunner.withInitializer { context ->
        context.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(*pairs)))
    }

    @Test
    fun `the five variables the platform injects bind to the redis settings`() {
        env(
            "SKELETON_REDIS_HOST" to "redis",
            "SKELETON_REDIS_PORT" to "6379",
            "SKELETON_REDIS_SSL_ENABLED" to "false",
            "SKELETON_REDIS_KEY_PREFIX" to "ovation:prod",
            "SKELETON_REDIS_PASSWORD" to "from-the-platform",
        ).run { context ->
            val properties = context.getBean(RedisCoreProperties::class.java)

            assertThat(properties.host).isEqualTo("redis")
            assertThat(properties.port).isEqualTo(6379)
            assertThat(properties.ssl.enabled).isFalse()
            assertThat(properties.keyPrefix).isEqualTo("ovation:prod")
            assertThat(properties.password).isEqualTo("from-the-platform")
        }
    }

    @Test
    fun `ssl is taken from the variable in both directions`() {
        env("SKELETON_REDIS_SSL_ENABLED" to "true").run { assertThat(it.getBean(RedisCoreProperties::class.java).ssl.enabled).isTrue() }
        env("SKELETON_REDIS_SSL_ENABLED" to "false").run { assertThat(it.getBean(RedisCoreProperties::class.java).ssl.enabled).isFalse() }
    }
}
