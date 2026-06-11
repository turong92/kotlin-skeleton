package dev.sumin.skeleton.redis.lock

import java.util.concurrent.TimeUnit
import org.redisson.api.RLock
import org.redisson.api.RedissonClient

fun interface DistributedLockBackend {
    fun getLock(key: String): DistributedLockHandle
}

interface DistributedLockHandle {
    fun tryLock(
        waitTime: Long,
        leaseTime: Long,
        timeUnit: TimeUnit,
    ): Boolean

    fun tryLock(
        waitTime: Long,
        timeUnit: TimeUnit,
    ): Boolean

    fun unlock()
}

class RedissonDistributedLockBackend(
    private val redissonClient: RedissonClient,
) : DistributedLockBackend {
    override fun getLock(key: String): DistributedLockHandle =
        try {
            RedissonDistributedLockHandle(redissonClient.getLock(key))
        } catch (e: Exception) {
            throw RedisLockBackendException("Redis lock backend failed to create lock for key=$key", e)
        }
}

private class RedissonDistributedLockHandle(
    private val lock: RLock,
) : DistributedLockHandle {
    override fun tryLock(
        waitTime: Long,
        leaseTime: Long,
        timeUnit: TimeUnit,
    ): Boolean =
        try {
            lock.tryLock(waitTime, leaseTime, timeUnit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RedisLockBackendException("Interrupted while acquiring Redis lock", e)
        } catch (e: Exception) {
            throw RedisLockBackendException("Redis lock acquisition failed", e)
        }

    override fun tryLock(
        waitTime: Long,
        timeUnit: TimeUnit,
    ): Boolean =
        try {
            lock.tryLock(waitTime, timeUnit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RedisLockBackendException("Interrupted while acquiring Redis lock", e)
        } catch (e: Exception) {
            throw RedisLockBackendException("Redis lock acquisition failed", e)
        }

    override fun unlock() {
        try {
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        } catch (e: Exception) {
            throw RedisLockBackendException("Redis lock release failed", e)
        }
    }
}
