package dev.sumin.skeleton.redis.lock

import java.time.Duration
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

data class DistributedLockRequest(
    val waitTime: Long,
    val leaseTime: Long,
    val timeUnit: TimeUnit,
    val retryAttempts: Int,
    val retryBackoff: Duration,
    val failurePolicy: LockFailurePolicy,
)

fun interface LockRetrySleeper {
    fun sleep(duration: Duration)
}

class ThreadSleepingLockRetrySleeper : LockRetrySleeper {
    override fun sleep(duration: Duration) {
        if (!duration.isPositive) return
        try {
            Thread.sleep(duration.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RedisLockBackendException("Interrupted during Redis lock retry backoff", e)
        }
    }
}

class DistributedLockExecutor(
    private val backend: DistributedLockBackend,
    private val sleeper: LockRetrySleeper = ThreadSleepingLockRetrySleeper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T> execute(
        key: String,
        request: DistributedLockRequest,
        action: () -> T,
    ): T? {
        val attempts = request.retryAttempts.coerceAtLeast(0)
        var backendFailure: RedisLockBackendException? = null

        for (attempt in 0..attempts) {
            try {
                val handle = backend.getLock(key)
                backendFailure = null
                if (tryAcquire(handle, request)) {
                    return runLocked(key, handle, action)
                }
            } catch (e: RedisLockBackendException) {
                backendFailure = e
            }

            if (attempt < attempts) {
                sleeper.sleep(request.retryBackoff)
            }
        }

        return backendFailure
            ?.let { handleBackendFailure(key, request, it, action) }
            ?: handleLockNotAcquired(key, request)
    }

    private fun tryAcquire(
        handle: DistributedLockHandle,
        request: DistributedLockRequest,
    ): Boolean =
        if (request.leaseTime < 0) {
            handle.tryLock(request.waitTime, request.timeUnit)
        } else {
            handle.tryLock(request.waitTime, request.leaseTime, request.timeUnit)
        }

    private fun <T> runLocked(
        key: String,
        handle: DistributedLockHandle,
        action: () -> T,
    ): T {
        try {
            return action()
        } finally {
            runCatching { handle.unlock() }
                .onFailure { error -> log.warn("Redis lock unlock failed for key={}: {}", key, error.message) }
        }
    }

    private fun <T> handleBackendFailure(
        key: String,
        request: DistributedLockRequest,
        failure: RedisLockBackendException,
        action: () -> T,
    ): T? =
        when (request.failurePolicy) {
            LockFailurePolicy.THROW -> throw failure
            LockFailurePolicy.SKIP -> {
                log.warn("Redis lock skipped after backend failure for key={}: {}", key, failure.message)
                null
            }
            LockFailurePolicy.PROCEED_ON_BACKEND_FAILURE -> {
                log.warn("Redis lock proceeding without lock after backend failure for key={}: {}", key, failure.message)
                action()
            }
        }

    private fun <T> handleLockNotAcquired(
        key: String,
        request: DistributedLockRequest,
    ): T? =
        when (request.failurePolicy) {
            LockFailurePolicy.SKIP -> {
                log.warn("Redis lock skipped because key={} was not acquired", key)
                null
            }
            LockFailurePolicy.THROW,
            LockFailurePolicy.PROCEED_ON_BACKEND_FAILURE,
            -> throw RedisLockNotAcquiredException("Redis lock was not acquired for key=$key")
        }
}
