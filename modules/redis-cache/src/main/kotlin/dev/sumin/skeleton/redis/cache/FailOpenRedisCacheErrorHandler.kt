package dev.sumin.skeleton.redis.cache

import org.slf4j.LoggerFactory
import org.springframework.cache.Cache
import org.springframework.cache.interceptor.CacheErrorHandler

class FailOpenRedisCacheErrorHandler : CacheErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleCacheGetError(
        exception: RuntimeException,
        cache: Cache,
        key: Any,
    ) {
        log.warn("Ignoring Redis cache get failure for cache={} key={}", cache.name, key, exception)
    }

    override fun handleCachePutError(
        exception: RuntimeException,
        cache: Cache,
        key: Any,
        value: Any?,
    ) {
        log.warn("Ignoring Redis cache put failure for cache={} key={}", cache.name, key, exception)
    }

    override fun handleCacheEvictError(
        exception: RuntimeException,
        cache: Cache,
        key: Any,
    ) {
        log.warn("Ignoring Redis cache evict failure for cache={} key={}", cache.name, key, exception)
    }

    override fun handleCacheClearError(
        exception: RuntimeException,
        cache: Cache,
    ) {
        log.warn("Ignoring Redis cache clear failure for cache={}", cache.name, exception)
    }
}
