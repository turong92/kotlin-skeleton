package dev.sumin.skeleton.redis.core

import java.util.function.Supplier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer

class RedisCoreAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java))

    @Test
    fun `creates redis beans without connecting to redis`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis.host=127.0.0.1",
                "skeleton.redis.port=6399",
                "skeleton.redis.key-prefix=kotlin-skeleton:test",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(RedisCoreProperties::class.java)
                assertThat(context).hasSingleBean(RedisKeyPrefixer::class.java)
                assertThat(context).hasSingleBean(RedisConnectionFactory::class.java)
                assertThat(context).hasSingleBean(StringRedisTemplate::class.java)
                assertThat(context).hasBean("jsonRedisTemplate")
            }
    }

    @Test
    fun `configures standalone lettuce connection factory`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis.host=redis.internal",
                "skeleton.redis.port=6380",
                "skeleton.redis.database=2",
            )
            .run { context ->
                val connectionFactory = context.getBean(RedisConnectionFactory::class.java)

                assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory::class.java)
                val lettuce = connectionFactory as LettuceConnectionFactory
                assertThat(lettuce.standaloneConfiguration.hostName).isEqualTo("redis.internal")
                assertThat(lettuce.standaloneConfiguration.port).isEqualTo(6380)
                assertThat(lettuce.database).isEqualTo(2)
            }
    }

    @Test
    fun `backs off when user provides connection factory and templates`() {
        val userConnectionFactory = LettuceConnectionFactory("localhost", 6379)
        val userStringTemplate = StringRedisTemplate(userConnectionFactory)
        val userJsonTemplate = RedisTemplate<String, Any>().apply {
            connectionFactory = userConnectionFactory
        }

        contextRunner
            .withBean(RedisConnectionFactory::class.java, Supplier { userConnectionFactory })
            .withBean(StringRedisTemplate::class.java, Supplier { userStringTemplate })
            .withBean("jsonRedisTemplate", RedisTemplate::class.java, Supplier { userJsonTemplate })
            .run { context ->
                assertThat(context.getBean(RedisConnectionFactory::class.java)).isSameAs(userConnectionFactory)
                assertThat(context.getBean(StringRedisTemplate::class.java)).isSameAs(userStringTemplate)
                assertThat(context.getBean("jsonRedisTemplate")).isSameAs(userJsonTemplate)
            }
    }

    @Test
    fun `creates redis serializers`() {
        contextRunner.run { context ->
            assertThat(context).hasBean("redisStringSerializer")
            assertThat(context).hasBean("redisJsonSerializer")
            assertThat(context.getBean("redisStringSerializer", RedisSerializer::class.java)).isNotNull()
            assertThat(context.getBean("redisJsonSerializer", RedisSerializer::class.java)).isNotNull()
        }
    }
}
