package dev.sumin.skeleton.redis.cache

import dev.sumin.skeleton.common.failure.FailureBoundary
import dev.sumin.skeleton.common.failure.FailurePolicy
import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.interceptor.CacheErrorHandler

class FailOpenRedisCacheErrorHandler : CacheErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = FailureBoundary(log)

    override fun handleCacheGetError(
        exception: RuntimeException,
        cache: Cache,
        key: Any,
    ) {
        continueAfterCacheFailure("redis.cache.get", exception, cacheContext(cache, key))
    }

    override fun handleCachePutError(
        exception: RuntimeException,
        cache: Cache,
        key: Any,
        value: Any?,
    ) {
        continueAfterCacheFailure("redis.cache.put", exception, cacheContext(cache, key))
    }

    override fun handleCacheEvictError(
        exception: RuntimeException,
        cache: Cache,
        key: Any,
    ) {
        continueAfterCacheFailure("redis.cache.evict", exception, cacheContext(cache, key))
    }

    override fun handleCacheClearError(
        exception: RuntimeException,
        cache: Cache,
    ) {
        continueAfterCacheFailure("redis.cache.clear", exception, mapOf("cache" to cache.name))
    }

    private fun continueAfterCacheFailure(
        operation: String,
        exception: RuntimeException,
        context: Map<String, Any?>,
    ) {
        failures.run(
            operation = operation,
            policy = FailurePolicy.LOG_AND_CONTINUE,
            context = context,
            fallback = {},
        ) {
            throw exception
        }
    }

    private fun cacheContext(
        cache: Cache,
        key: Any,
    ): Map<String, Any?> =
        mapOf(
            "cache" to cache.name,
            "key" to key,
        )
}
