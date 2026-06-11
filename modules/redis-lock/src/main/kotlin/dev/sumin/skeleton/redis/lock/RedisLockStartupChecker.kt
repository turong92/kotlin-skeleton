package dev.sumin.skeleton.redis.lock

import org.springframework.beans.factory.SmartInitializingSingleton

class RedisLockStartupChecker(
    private val properties: RedisLockProperties,
    private val verifier: RedisLockBackendVerifier,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        if (properties.startupCheck.enabled) {
            verifier.verify()
        }
    }
}
