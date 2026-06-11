package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
import dev.sumin.skeleton.redis.core.RedisCoreProperties
import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.redisson.config.SslVerificationMode
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [RedisCoreAutoConfiguration::class])
@EnableConfigurationProperties(RedisLockProperties::class)
@ConditionalOnProperty(
    prefix = "skeleton.redis-lock",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class RedisLockAutoConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    @Suppress("DEPRECATION")
    fun redissonClient(
        redisProperties: RedisCoreProperties,
        lockProperties: RedisLockProperties,
    ): RedissonClient {
        val config = Config()
        val singleServer = config.useSingleServer()
        val protocol = if (redisProperties.ssl.enabled) "rediss" else "redis"

        singleServer
            .setAddress("$protocol://${redisProperties.host}:${redisProperties.port}")
            .setDatabase(redisProperties.database)
            .setRetryAttempts(lockProperties.redisson.retryAttempts)
            .setRetryInterval(lockProperties.redisson.retryInterval.toMillis().toInt())
            .setTimeout(lockProperties.redisson.timeout.toMillis().toInt())
        if (redisProperties.username.isNotBlank()) {
            singleServer.setUsername(redisProperties.username)
        }
        if (redisProperties.password.isNotBlank()) {
            singleServer.setPassword(redisProperties.password)
        }
        if (redisProperties.ssl.enabled && redisProperties.ssl.disablePeerVerificationLocal) {
            singleServer.setSslVerificationMode(SslVerificationMode.NONE)
        }

        return Redisson.create(config)
    }

    @Bean
    @ConditionalOnMissingBean
    fun distributedLockBackend(redissonClient: RedissonClient): DistributedLockBackend =
        RedissonDistributedLockBackend(redissonClient)

    @Bean
    @ConditionalOnMissingBean
    fun lockRetrySleeper(): LockRetrySleeper =
        ThreadSleepingLockRetrySleeper()

    @Bean
    @ConditionalOnMissingBean
    fun distributedLockExecutor(
        backend: DistributedLockBackend,
        sleeper: LockRetrySleeper,
    ): DistributedLockExecutor =
        DistributedLockExecutor(backend = backend, sleeper = sleeper)

    @Bean
    @ConditionalOnMissingBean
    fun distributedLockKeyResolver(redisKeyPrefixer: RedisKeyPrefixer): DistributedLockKeyResolver =
        DistributedLockKeyResolver(redisKeyPrefixer)

    @Bean
    @ConditionalOnMissingBean
    fun redisLockBackendVerifier(
        redissonClient: RedissonClient,
        properties: RedisLockProperties,
    ): RedisLockBackendVerifier =
        RedissonRedisLockBackendVerifier(redissonClient = redissonClient, properties = properties)

    @Bean
    @ConditionalOnMissingBean
    fun redisLockStartupChecker(
        properties: RedisLockProperties,
        verifier: RedisLockBackendVerifier,
    ): RedisLockStartupChecker =
        RedisLockStartupChecker(properties = properties, verifier = verifier)

    @Bean
    @ConditionalOnMissingBean
    fun distributedLockAspect(
        keyResolver: DistributedLockKeyResolver,
        executor: DistributedLockExecutor,
        properties: RedisLockProperties,
    ): DistributedLockAspect =
        DistributedLockAspect(keyResolver = keyResolver, executor = executor, properties = properties)
}
