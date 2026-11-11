package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * 종료 신호(SIGTERM)에 일꾼이 짧게 끝난다 - 새 묶음을 집지 않고, 이미 집은 묶음의 아직 안 돈 잡은 곧바로 PENDING 으로 돌려주고(시도를 쓰지 않는다),
 * 도는 잡은 [JobQueueProperties.shutdownGrace] 동안 기다린 뒤 끊는다. 끊긴 잡도 시도를 쓰지 않고 돌아온다 (스테일 복구 10분을 기다리지 않는다).
 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [JobQueueTestApplication::class], properties = ["skeleton.job-queue.worker-enabled=false"])
class JobQueueShutdownIntegrationTest {
    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties

    private val time = TimeProvider { Instant.now() }

    private class GateHandler(override val type: String) : JobHandler {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicInteger()
        val finished = AtomicInteger()
        override fun handle(job: Job) {
            entered.countDown()
            try {
                release.await()
                finished.incrementAndGet()
            } catch (e: InterruptedException) {
                interrupted.incrementAndGet()
                throw e
            }
        }
    }

    private fun worker(gate: GateHandler, grace: Duration) =
        JobQueueWorker(repository, listOf(gate), properties.copy(batchSize = 3, pollInterval = Duration.ofMillis(20), shutdownGrace = grace), time)

    @Test
    fun `stop waits for the running job, and the rest of the claimed batch goes back untouched`() {
        val gate = GateHandler("gate-wait")
        val ids = (1..3).map { queue.enqueue("gate-wait", """{"n":$it}""") }
        val worker = worker(gate, Duration.ofSeconds(10))
        worker.start()
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))

        val stopper = Thread { worker.stop() }.apply { start() }
        stopper.join(300)
        assertTrue(stopper.isAlive, "stop must wait for the running job")
        gate.release.countDown()
        stopper.join(10_000)
        assertFalse(stopper.isAlive)

        assertEquals(1, gate.finished.get())
        val jobs = ids.map { repository.findById(it)!! }
        assertEquals(1, jobs.count { it.status == JobStatus.DONE })
        val rest = jobs.filter { it.status != JobStatus.DONE }
        assertEquals(2, rest.size)
        rest.forEach {
            assertEquals(JobStatus.PENDING, it.status)
            assertEquals(0, it.attempts, "a job given back at shutdown has not used an attempt")
            assertEquals(null, it.lockedBy)
        }
    }

    @Test
    fun `the worker stops in the web server's shutdown phase so the two waits overlap instead of adding up`() {
        val worker = worker(GateHandler("gate-phase"), Duration.ofSeconds(1))
        assertTrue(worker is org.springframework.context.SmartLifecycle)
        assertEquals(org.springframework.context.SmartLifecycle.DEFAULT_PHASE - 1024, worker.phase) // 웹 서버 정상 종료 단계와 같다
        assertFalse(worker.isRunning)
        worker.start()
        assertTrue(worker.isRunning)
        val stopped = CountDownLatch(1)
        worker.stop { stopped.countDown() }
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
        assertFalse(worker.isRunning)
    }

    @Test
    fun `a job still running after the grace is interrupted and goes back without using an attempt`() {
        val gate = GateHandler("gate-cut")
        val id = queue.enqueue("gate-cut", "{}")
        val worker = worker(gate, Duration.ofMillis(100))
        worker.start()
        assertTrue(gate.entered.await(10, TimeUnit.SECONDS))

        worker.stop()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (repository.findById(id)!!.status != JobStatus.PENDING && System.nanoTime() < deadline) Thread.sleep(20)
        val job = repository.findById(id)!!
        assertEquals(1, gate.interrupted.get())
        assertEquals(JobStatus.PENDING, job.status)
        assertEquals(0, job.attempts)
        assertEquals(null, job.lockedBy)
    }
}
