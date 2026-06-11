package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisFixedWindowRateLimitStoreTest {
    @Test
    fun `uses prefixed fixed-window key and returns remaining capacity`() {
        val counter = RecordingCounter(1)
        val store = RedisFixedWindowRateLimitStore(
            counter = counter,
            redisKeyPrefixer = RedisKeyPrefixer("app"),
            properties = RedisRateLimitProperties(keyNamespace = "api"),
        )

        val decision = store.consume(
            key = "principal:alice",
            capacity = 2,
            windowMillis = 1000,
            now = Instant.ofEpochMilli(2345),
        )

        assertThat(counter.calls).containsExactly(CounterCall("app:rate-limit:api:principal:alice:2000", 1000))
        assertThat(decision.allowed).isTrue()
        assertThat(decision.limit).isEqualTo(2)
        assertThat(decision.remaining).isEqualTo(1)
        assertThat(decision.resetAt).isEqualTo(Instant.ofEpochMilli(3000))
    }

    @Test
    fun `rejects when redis count exceeds capacity`() {
        val store = RedisFixedWindowRateLimitStore(
            counter = RecordingCounter(3),
            redisKeyPrefixer = RedisKeyPrefixer("app"),
            properties = RedisRateLimitProperties(),
        )

        val decision = store.consume("ip:127.0.0.1", 2, 1000, Instant.ofEpochMilli(1000))

        assertThat(decision.allowed).isFalse()
        assertThat(decision.remaining).isZero()
    }

    @Test
    fun `allows request on redis failure when fail-open`() {
        val store = RedisFixedWindowRateLimitStore(
            counter = FailingCounter,
            redisKeyPrefixer = RedisKeyPrefixer("app"),
            properties = RedisRateLimitProperties(failurePolicy = RedisRateLimitProperties.FailurePolicy.FAIL_OPEN),
        )

        val decision = store.consume("ip:127.0.0.1", 2, 1000, Instant.ofEpochMilli(1000))

        assertThat(decision.allowed).isTrue()
        assertThat(decision.remaining).isEqualTo(2)
    }

    @Test
    fun `rejects request on redis failure when fail-closed`() {
        val store = RedisFixedWindowRateLimitStore(
            counter = FailingCounter,
            redisKeyPrefixer = RedisKeyPrefixer("app"),
            properties = RedisRateLimitProperties(failurePolicy = RedisRateLimitProperties.FailurePolicy.FAIL_CLOSED),
        )

        val decision = store.consume("ip:127.0.0.1", 2, 1000, Instant.ofEpochMilli(1000))

        assertThat(decision.allowed).isFalse()
        assertThat(decision.remaining).isZero()
    }

    private data class CounterCall(
        val key: String,
        val windowMillis: Long,
    )

    private class RecordingCounter(
        private val nextCount: Long,
    ) : RedisFixedWindowCounter {
        val calls = mutableListOf<CounterCall>()

        override fun increment(
            key: String,
            windowMillis: Long,
        ): Long {
            calls += CounterCall(key, windowMillis)
            return nextCount
        }
    }

    private object FailingCounter : RedisFixedWindowCounter {
        override fun increment(
            key: String,
            windowMillis: Long,
        ): Long = throw IllegalStateException("redis unavailable")
    }
}
