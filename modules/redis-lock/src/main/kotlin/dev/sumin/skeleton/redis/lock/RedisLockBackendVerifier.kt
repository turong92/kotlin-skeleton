package dev.sumin.skeleton.redis.lock

import org.redisson.api.RedissonClient

fun interface RedisLockBackendVerifier {
    fun verify()
}

class RedissonRedisLockBackendVerifier(
    private val redissonClient: RedissonClient,
    private val properties: RedisLockProperties,
) : RedisLockBackendVerifier {
    override fun verify() {
        try {
            redissonClient.getBucket<Any>(properties.startupCheck.key).isExists
        } catch (e: Exception) {
            throw RedisLockBackendException("Redis lock startup check failed", e)
        }
    }
}
