package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

/** 깨우기가 겹쳐도 판은 한 번에 하나 - 둘째는 일감을 건드리지 않고 busy 로 돌아가 조금 뒤 다시 부르게 한다 */
class DrainSingleFlightTest {
    private val now = Instant.parse("2026-11-18T00:00:00Z")
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val claims = CopyOnWriteArrayList<String>()

    /** claim 에서 멈추는 저장소 - 첫 판이 도는 중인 상태를 만든다 */
    private val repository: JdbcJobRepository = Mockito.mock(JdbcJobRepository::class.java) { call ->
        when (call.method.name) {
            "recoverStale" -> StaleRecovery(0, emptyList())
            "claim" -> { claims += "claim"; entered.countDown(); release.await(10, TimeUnit.SECONDS); emptyList<Job>() }
            else -> Mockito.RETURNS_DEFAULTS.answer(call)
        }
    }

    @Test
    fun `second concurrent drain returns busy and re-requests after busyRetry`() {
        val requests = CopyOnWriteArrayList<Instant>()
        val properties = JobQueueProperties(mode = JobQueueProperties.Mode.ON_REQUEST, wake = JobQueueProperties.Wake(busyRetry = Duration.ofSeconds(30)))
        val worker = JobQueueWorker(repository, emptyList(), properties, TimeProvider { now }, jobWake = JobWake { requests += it })

        var first: DrainResult? = null
        val running = thread { first = worker.drain(Duration.ofSeconds(25)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "첫 판이 돌고 있어야 한다")

        val second = worker.drain(Duration.ofSeconds(25))

        assertTrue(second.busy)
        assertEquals(0, second.rounds)
        assertEquals(listOf(now.plusSeconds(30)), requests.toList())
        assertEquals(1, claims.size, "둘째는 일감을 집으러 가지 않는다")

        release.countDown()
        running.join(10_000)
        assertFalse(first!!.busy)
        assertEquals(listOf(now.plusSeconds(30)), requests.toList(), "비어 있는 큐는 다시 무장하지 않는다")

        // 첫 판이 끝났으니 다음 깨우기는 busy 가 아니다
        assertFalse(worker.drain(Duration.ofSeconds(25)).busy)
    }
}
