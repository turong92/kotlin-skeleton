package dev.sumin.skeleton.redis.cache

import java.time.Duration

class NamedRedisCacheRegistry(
    val defaultTtl: Duration,
    private val cacheTtls: Map<String, Duration>,
) {
    val cacheNames: Set<String> = cacheTtls.keys

    fun cacheTtl(cacheName: String): Duration =
        cacheTtls[cacheName] ?: defaultTtl

    fun ttlByCacheName(): Map<String, Duration> =
        cacheTtls.toMap()

    companion object {
        fun from(properties: RedisCacheProperties): NamedRedisCacheRegistry =
            NamedRedisCacheRegistry(
                defaultTtl = properties.defaultTtl,
                cacheTtls = properties.caches.mapValues { (_, spec) -> spec.ttl ?: properties.defaultTtl },
            )
    }
}
