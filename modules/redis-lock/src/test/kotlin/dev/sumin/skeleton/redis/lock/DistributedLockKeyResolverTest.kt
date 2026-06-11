package dev.sumin.skeleton.redis.lock

import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DistributedLockKeyResolverTest {
    private val resolver = DistributedLockKeyResolver(RedisKeyPrefixer("kotlin-skeleton:local"))
    private val target = Fixture(accountId = 42)

    @Test
    fun `resolves literal key with global redis prefix`() {
        val method = Fixture::class.java.getDeclaredMethod("literal")
        val annotation = method.getAnnotation(DistributedLock::class.java)

        val key = resolver.resolve(target = target, method = method, args = emptyArray(), annotation = annotation)

        assertThat(key).isEqualTo("kotlin-skeleton:local:lock:daily:job")
    }

    @Test
    fun `resolves indexed SpEL key`() {
        val method = Fixture::class.java.getDeclaredMethod("byIndex", String::class.java)
        val annotation = method.getAnnotation(DistributedLock::class.java)

        val key = resolver.resolve(target = target, method = method, args = arrayOf("order-1"), annotation = annotation)

        assertThat(key).isEqualTo("kotlin-skeleton:local:lock:order:order-1")
    }

    @Test
    fun `resolves target SpEL key`() {
        val method = Fixture::class.java.getDeclaredMethod("byTarget")
        val annotation = method.getAnnotation(DistributedLock::class.java)

        val key = resolver.resolve(target = target, method = method, args = emptyArray(), annotation = annotation)

        assertThat(key).isEqualTo("kotlin-skeleton:local:lock:account:42")
    }

    private class Fixture(
        private val accountId: Long,
    ) {
        @DistributedLock(keyPrefix = "daily", key = "job")
        fun literal() = Unit

        @DistributedLock(keyPrefix = "order", key = "#p0")
        fun byIndex(orderId: String) = orderId

        @DistributedLock(keyPrefix = "account", key = "#target.currentAccountId()")
        fun byTarget() = Unit

        fun currentAccountId(): Long = accountId
    }
}
