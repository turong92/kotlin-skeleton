package dev.sumin.skeleton.redis.ratelimit

import dev.sumin.skeleton.redis.core.RedisKeyPrefixer
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectDatasource
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
    fun `release gives one count back on the same prefixed key as the consume, and a failing Redis never throws`() {
        val released = mutableListOf<String>()
        val counter = object : RedisFixedWindowCounter {
            override fun increment(key: String, windowMillis: Long): Long = 1
            override fun decrement(key: String) { released += key }
        }
        val store = RedisFixedWindowRateLimitStore(counter, RedisKeyPrefixer("app"), RedisRateLimitProperties(keyNamespace = "api"))
        store.release("board:guest-secret:row:p1", 1000, Instant.ofEpochMilli(2345))
        assertThat(released).containsExactly("app:rate-limit:api:board:guest-secret:row:p1:2000")

        val broken = object : RedisFixedWindowCounter {
            override fun increment(key: String, windowMillis: Long): Long = 1
            override fun decrement(key: String) = throw IllegalStateException("down")
        }
        RedisFixedWindowRateLimitStore(broken, RedisKeyPrefixer("app"), RedisRateLimitProperties()).release("k", 1000, Instant.ofEpochMilli(1))
    }

    @Test
    fun `a counter that cannot give a count back leaves the store consuming as before`() {
        val store = RedisFixedWindowRateLimitStore(RecordingCounter(1), RedisKeyPrefixer("app"), RedisRateLimitProperties())
        store.release("k", 1000, Instant.ofEpochMilli(1))   // 기본 decrement 는 아무것도 하지 않는다
    }

    @Test
    fun `the same key in two projects is two counters`() {
        val counter = RecordingCounter(1)
        val store = RedisFixedWindowRateLimitStore(counter, RedisKeyPrefixer("app"), RedisRateLimitProperties(keyNamespace = "api"))
        fun project(id: String) = Project(id, listOf("$id.test"), "p$id", ProjectDatasource(), emptyMap())

        ProjectContext.runIn(project("a")) { store.consume("ip:1.2.3.4", 2, 1000, Instant.ofEpochMilli(2345)) }
        ProjectContext.runIn(project("b")) { store.consume("ip:1.2.3.4", 2, 1000, Instant.ofEpochMilli(2345)) }

        assertThat(counter.calls.map { it.key }).containsExactly("app:project:a:rate-limit:api:ip:1.2.3.4:2000", "app:project:b:rate-limit:api:ip:1.2.3.4:2000")
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
