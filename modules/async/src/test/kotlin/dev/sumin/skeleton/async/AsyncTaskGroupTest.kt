package dev.sumin.skeleton.async

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AsyncTaskGroupTest {
    @Test
    fun `waitAll collects named successes failures and duration`() {
        val success = CompletableFuture.completedFuture("ok")
        val failure = CompletableFuture<String>().also {
            it.completeExceptionally(IllegalStateException("boom"))
        }

        val result = AsyncTaskGroup.waitAll(
            tasks = mapOf(
                "success-task" to success,
                "failure-task" to failure,
            ),
        )

        assertEquals(listOf("success-task"), result.succeeded)
        assertEquals(1, result.failed.size)
        assertEquals("failure-task", result.failed.single().name)
        assertIs<IllegalStateException>(result.failed.single().error)
        assertTrue(result.durationMillis >= 0)
        assertTrue(result.hasFailures)
    }

    @Test
    fun `waitAll records timeout as named failure without blocking forever`() {
        val never = CompletableFuture<String>()

        val result = AsyncTaskGroup.waitAll(
            tasks = mapOf("slow-task" to never),
            timeout = Duration.ofMillis(10),
        )

        assertEquals(emptyList(), result.succeeded)
        assertEquals("slow-task", result.failed.single().name)
        assertIs<TimeoutException>(result.failed.single().error)
    }

    @Test
    fun `throwIfFailures throws one exception with suppressed task failures`() {
        val result = AsyncTaskGroup.waitAll(
            tasks = mapOf(
                "bad-a" to failedFuture(IllegalArgumentException("bad-a")),
                "bad-b" to failedFuture(IllegalStateException("bad-b")),
            ),
        )

        val exception = assertFailsWith<AsyncTaskGroupException> {
            result.throwIfFailures()
        }

        assertEquals("Async task group failed for tasks: bad-a, bad-b", exception.message)
        assertEquals(2, exception.suppressed.size)
    }

    private fun failedFuture(error: Throwable): CompletableFuture<String> =
        CompletableFuture<String>().also { it.completeExceptionally(error) }
}
