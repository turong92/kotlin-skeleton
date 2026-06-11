package dev.sumin.skeleton.redis.core

import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import tools.jackson.module.kotlin.kotlinModule

@AutoConfiguration
@EnableConfigurationProperties(RedisCoreProperties::class)
class RedisCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun redisKeyPrefixer(properties: RedisCoreProperties): RedisKeyPrefixer =
        RedisKeyPrefixer(properties.keyPrefix)

    @Bean("redisStringSerializer")
    @ConditionalOnMissingBean(name = ["redisStringSerializer"])
    fun redisStringSerializer(): RedisSerializer<String> =
        StringRedisSerializer()

    @Bean("redisJsonSerializer")
    @ConditionalOnMissingBean(name = ["redisJsonSerializer"])
    fun redisJsonSerializer(): RedisSerializer<Any> {
        val typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("dev.sumin.skeleton.")
            .allowIfSubType("java.time.")
            .allowIfSubType("java.util.")
            .allowIfSubTypeIsArray()
            .allowSubTypesWithExplicitDeserializer()
            .build()

        return GenericJacksonJsonRedisSerializer.builder()
            .enableDefaultTyping(typeValidator)
            .customize { builder -> builder.addModule(kotlinModule()) }
            .build()
    }

    @Bean
    @ConditionalOnMissingBean
    fun redisConnectionFactory(properties: RedisCoreProperties): RedisConnectionFactory {
        val standalone = RedisStandaloneConfiguration().apply {
            hostName = properties.host
            port = properties.port
            database = properties.database
            if (properties.username.isNotBlank()) {
                username = properties.username
            }
            if (properties.password.isNotBlank()) {
                password = RedisPassword.of(properties.password)
            }
        }

        val clientOptions = ClientOptions.builder()
            .socketOptions(
                SocketOptions.builder()
                    .connectTimeout(properties.timeout.connect)
                    .build(),
            )
            .build()

        val clientBuilder = LettuceClientConfiguration.builder()
            .commandTimeout(properties.timeout.command)
            .clientOptions(clientOptions)

        val client = if (properties.ssl.enabled) {
            val sslBuilder = clientBuilder.useSsl()
            if (properties.ssl.disablePeerVerificationLocal) {
                sslBuilder.disablePeerVerification()
            }
            sslBuilder.build()
        } else {
            clientBuilder.build()
        }

        return LettuceConnectionFactory(standalone, client)
    }

    @Bean
    @ConditionalOnMissingBean
    fun stringRedisTemplate(redisConnectionFactory: RedisConnectionFactory): StringRedisTemplate =
        StringRedisTemplate(redisConnectionFactory)

    @Bean("jsonRedisTemplate")
    @ConditionalOnMissingBean(name = ["jsonRedisTemplate"])
    fun jsonRedisTemplate(
        redisConnectionFactory: RedisConnectionFactory,
        @Qualifier("redisStringSerializer") stringSerializer: RedisSerializer<String>,
        @Qualifier("redisJsonSerializer") jsonSerializer: RedisSerializer<Any>,
    ): RedisTemplate<String, Any> =
        RedisTemplate<String, Any>().apply {
            connectionFactory = redisConnectionFactory
            keySerializer = stringSerializer
            hashKeySerializer = stringSerializer
            valueSerializer = jsonSerializer
            hashValueSerializer = jsonSerializer
            afterPropertiesSet()
        }
}
