package dev.sumin.skeleton.redis.core

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisCorePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java))

    @Test
    fun `binds default redis properties`() {
        contextRunner.run { context ->
            val properties = context.getBean(RedisCoreProperties::class.java)

            assertThat(properties.mode).isEqualTo(RedisCoreProperties.Mode.STANDALONE)
            assertThat(properties.host).isEqualTo("localhost")
            assertThat(properties.port).isEqualTo(6379)
            assertThat(properties.database).isEqualTo(0)
            assertThat(properties.username).isEqualTo("")
            assertThat(properties.password).isEqualTo("")
            assertThat(properties.ssl.enabled).isFalse()
            assertThat(properties.ssl.disablePeerVerificationLocal).isTrue()
            assertThat(properties.timeout.connect).isEqualTo(Duration.ofSeconds(2))
            assertThat(properties.timeout.command).isEqualTo(Duration.ofSeconds(2))
            assertThat(properties.keyPrefix).isEqualTo("kotlin-skeleton")
            assertThat(properties.json.trustedPackages)
                .containsExactly("dev.sumin.skeleton", "java.time", "java.util")
        }
    }

    @Test
    fun `binds env style redis overrides`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis.mode=standalone",
                "skeleton.redis.host=redis.internal",
                "skeleton.redis.port=6380",
                "skeleton.redis.database=3",
                "skeleton.redis.username=app",
                "skeleton.redis.password=secret-from-env-or-ssm",
                "skeleton.redis.ssl.enabled=true",
                "skeleton.redis.ssl.disable-peer-verification-local=false",
                "skeleton.redis.timeout.connect=1500ms",
                "skeleton.redis.timeout.command=2500ms",
                "skeleton.redis.key-prefix=kotlin-skeleton:dev",
                "skeleton.redis.json.trusted-packages=dev.sumin.skeleton,java.time,java.util,com.example.redis",
            )
            .run { context ->
                val properties = context.getBean(RedisCoreProperties::class.java)

                assertThat(properties.host).isEqualTo("redis.internal")
                assertThat(properties.port).isEqualTo(6380)
                assertThat(properties.database).isEqualTo(3)
                assertThat(properties.username).isEqualTo("app")
                assertThat(properties.password).isEqualTo("secret-from-env-or-ssm")
                assertThat(properties.ssl.enabled).isTrue()
                assertThat(properties.ssl.disablePeerVerificationLocal).isFalse()
                assertThat(properties.timeout.connect).isEqualTo(Duration.ofMillis(1500))
                assertThat(properties.timeout.command).isEqualTo(Duration.ofMillis(2500))
                assertThat(properties.keyPrefix).isEqualTo("kotlin-skeleton:dev")
                assertThat(properties.json.trustedPackages)
                    .containsExactly("dev.sumin.skeleton", "java.time", "java.util", "com.example.redis")
            }
    }
}
