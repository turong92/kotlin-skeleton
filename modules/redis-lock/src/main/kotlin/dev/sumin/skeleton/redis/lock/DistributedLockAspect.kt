package dev.sumin.skeleton.redis.lock

import java.time.Duration
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
class DistributedLockAspect(
    private val keyResolver: DistributedLockKeyResolver,
    private val executor: DistributedLockExecutor,
    private val properties: RedisLockProperties,
) {
    @Around("@annotation(distributedLock)")
    fun applyLock(
        joinPoint: ProceedingJoinPoint,
        distributedLock: DistributedLock,
    ): Any? {
        val method = (joinPoint.signature as? MethodSignature)?.method
            ?: throw DistributedLockKeyException("DistributedLock can only be applied to methods")
        val key = keyResolver.resolve(
            target = joinPoint.target,
            method = method,
            args = joinPoint.args ?: emptyArray(),
            annotation = distributedLock,
        )

        return executor.execute(
            key = key,
            request = distributedLock.toRequest(properties),
        ) {
            joinPoint.proceed()
        }
    }

    private fun DistributedLock.toRequest(properties: RedisLockProperties): DistributedLockRequest =
        DistributedLockRequest(
            waitTime = waitTime,
            leaseTime = leaseTime,
            timeUnit = timeUnit,
            retryAttempts = retryAttempts.takeIf { it >= 0 } ?: properties.retry.attempts,
            retryBackoff = retryBackoffMillis
                .takeIf { it >= 0 }
                ?.let(Duration::ofMillis)
                ?: properties.retry.backoff,
            failurePolicy = failurePolicy,
        )
}
