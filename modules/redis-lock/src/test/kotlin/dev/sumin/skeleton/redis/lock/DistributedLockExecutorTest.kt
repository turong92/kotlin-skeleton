package dev.sumin.skeleton.redis.lock

import java.time.Duration
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DistributedLockExecutorTest {
    @Test
    fun `retries lock acquisition before running action`() {
        val backend = RecordingLockBackend(
            outcomes = ArrayDeque(
                listOf(
                    LockOutcome.NotAcquired,
                    LockOutcome.NotAcquired,
                    LockOutcome.Acquired,
                ),
            ),
        )
        val sleeper = RecordingLockRetrySleeper()
        val executor = DistributedLockExecutor(backend = backend, sleeper = sleeper)

        val result = executor.execute(
            key = "app:lock:order:1",
            request = request(retryAttempts = 3),
        ) {
            "done"
        }

        assertThat(result).isEqualTo("done")
        assertThat(backend.attempts).isEqualTo(3)
        assertThat(backend.handles.single { it.acquired }.unlocked).isTrue()
        assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(200))
    }

    @Test
    fun `throws after retry attempts when lock is not acquired`() {
        val backend = RecordingLockBackend(
            outcomes = ArrayDeque(List(4) { LockOutcome.NotAcquired }),
        )
        val executor = DistributedLockExecutor(backend = backend, sleeper = RecordingLockRetrySleeper())

        assertThatThrownBy {
            executor.execute(key = "app:lock:order:1", request = request(retryAttempts = 3)) {
                "should-not-run"
            }
        }.isInstanceOf(RedisLockNotAcquiredException::class.java)

        assertThat(backend.attempts).isEqualTo(4)
    }

    @Test
    fun `skips action after retry attempts when policy is skip`() {
        val backend = RecordingLockBackend(
            outcomes = ArrayDeque(List(2) { LockOutcome.NotAcquired }),
        )
        val executor = DistributedLockExecutor(backend = backend, sleeper = RecordingLockRetrySleeper())

        val result = executor.execute(
            key = "app:lock:order:1",
            request = request(retryAttempts = 1, failurePolicy = LockFailurePolicy.SKIP),
        ) {
            "should-not-run"
        }

        assertThat(result).isNull()
        assertThat(backend.attempts).isEqualTo(2)
    }

    @Test
    fun `proceeds after backend failures when policy allows backend failover`() {
        val backend = RecordingLockBackend(
            outcomes = ArrayDeque(List(3) { LockOutcome.BackendFailure }),
        )
        val executor = DistributedLockExecutor(backend = backend, sleeper = RecordingLockRetrySleeper())

        val result = executor.execute(
            key = "app:lock:order:1",
            request = request(retryAttempts = 2, failurePolicy = LockFailurePolicy.PROCEED_ON_BACKEND_FAILURE),
        ) {
            "done-without-lock"
        }

        assertThat(result).isEqualTo("done-without-lock")
        assertThat(backend.attempts).isEqualTo(3)
    }

    @Test
    fun `uses watchdog lock mode when lease time is negative`() {
        val backend = RecordingLockBackend(outcomes = ArrayDeque(listOf(LockOutcome.Acquired)))
        val executor = DistributedLockExecutor(backend = backend, sleeper = RecordingLockRetrySleeper())

        executor.execute(
            key = "app:lock:order:1",
            request = request(leaseTime = -1),
        ) {
            "done"
        }

        assertThat(backend.handles.single().watchdogModeUsed).isTrue()
    }

    private fun request(
        retryAttempts: Int = 3,
        failurePolicy: LockFailurePolicy = LockFailurePolicy.THROW,
        leaseTime: Long = 60,
    ): DistributedLockRequest =
        DistributedLockRequest(
            waitTime = 1,
            leaseTime = leaseTime,
            timeUnit = TimeUnit.SECONDS,
            retryAttempts = retryAttempts,
            retryBackoff = Duration.ofMillis(200),
            failurePolicy = failurePolicy,
        )
}

private sealed interface LockOutcome {
    data object Acquired : LockOutcome
    data object NotAcquired : LockOutcome
    data object BackendFailure : LockOutcome
}

private class RecordingLockBackend(
    private val outcomes: ArrayDeque<LockOutcome>,
) : DistributedLockBackend {
    val handles = mutableListOf<RecordingLockHandle>()
    var attempts: Int = 0

    override fun getLock(key: String): DistributedLockHandle {
        attempts += 1
        val outcome = outcomes.removeFirstOrNull() ?: LockOutcome.NotAcquired
        if (outcome == LockOutcome.BackendFailure) {
            throw RedisLockBackendException("backend failed")
        }
        return RecordingLockHandle(outcome == LockOutcome.Acquired).also { handles += it }
    }
}

private class RecordingLockHandle(
    val acquired: Boolean,
) : DistributedLockHandle {
    var unlocked: Boolean = false
    var watchdogModeUsed: Boolean = false

    override fun tryLock(
        waitTime: Long,
        leaseTime: Long,
        timeUnit: TimeUnit,
    ): Boolean = acquired

    override fun tryLock(
        waitTime: Long,
        timeUnit: TimeUnit,
    ): Boolean {
        watchdogModeUsed = true
        return acquired
    }

    override fun unlock() {
        unlocked = true
    }
}

private class RecordingLockRetrySleeper : LockRetrySleeper {
    val sleeps = mutableListOf<Duration>()

    override fun sleep(duration: Duration) {
        sleeps += duration
    }
}
