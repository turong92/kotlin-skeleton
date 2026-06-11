package dev.sumin.skeleton.redis.lock

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.redisson.api.RedissonClient

internal object RedissonClientProxy {
    fun create(): RedissonClient =
        Proxy.newProxyInstance(
            RedissonClient::class.java.classLoader,
            arrayOf(RedissonClient::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "toString" -> "RedissonClientProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> throw UnsupportedOperationException(method.name)
                }
            },
        ) as RedissonClient
}
