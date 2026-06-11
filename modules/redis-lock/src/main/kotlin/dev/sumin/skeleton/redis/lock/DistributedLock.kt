package dev.sumin.skeleton.redis.lock

import java.util.concurrent.TimeUnit

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DistributedLock(
    val keyPrefix: String,
    val key: String,
    val waitTime: Long = 3,
    val leaseTime: Long = -1,
    val timeUnit: TimeUnit = TimeUnit.SECONDS,
    val retryAttempts: Int = -1,
    val retryBackoffMillis: Long = -1,
    val failurePolicy: LockFailurePolicy = LockFailurePolicy.THROW,
)
