package dev.sumin.skeleton.app.workbench.api

import dev.sumin.skeleton.redis.core.RedisCoreProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 홈서버 배포에서 겪은 사고: prod 프로필의 `redis.ssl.enabled: true`(관리형 Redis 전제)가 사설 네트워크의 평문 Redis 에서 TLS 핸드셰이크 타임아웃으로
 * 기동을 깼다. 플랫폼은 `<ENV_PREFIX>_REDIS_SSL_ENABLED=false` 를 넣는다 — 환경변수가 prod 프로필 yml 을 이겨야 한다.
 * 진짜 application-prod.yml 을 읽고, 진짜 `systemEnvironment` 속성 원본 모양으로 환경변수를 넣는다.
 */
class ProdRedisSslEnvironmentTest {
    private fun sslEnabled(vararg env: Pair<String, String>): Boolean {
        var enabled: Boolean? = null
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withInitializer { context ->
                if (env.isNotEmpty()) {
                    context.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(*env)))
                }
            }
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertThat(context).hasNotFailed()
                enabled = Binder.get(context.environment).bind("skeleton.redis", RedisCoreProperties::class.java).get().ssl.enabled
            }
        return enabled!!
    }

    @Test
    fun `prod keeps tls on by default - managed redis is the premise of the profile`() {
        assertEquals(true, sslEnabled())
    }

    @Test
    fun `the injected variable wins over the prod profile and turns tls off`() {
        assertEquals(false, sslEnabled("SKELETON_REDIS_SSL_ENABLED" to "false"))
    }
}
