package dev.sumin.skeleton.redis.lock

open class RedisLockException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class RedisLockNotAcquiredException(
    message: String,
) : RedisLockException(message)

class RedisLockBackendException(
    message: String,
    cause: Throwable? = null,
) : RedisLockException(message, cause)

class DistributedLockKeyException(
    message: String,
    cause: Throwable? = null,
) : RedisLockException(message, cause)
