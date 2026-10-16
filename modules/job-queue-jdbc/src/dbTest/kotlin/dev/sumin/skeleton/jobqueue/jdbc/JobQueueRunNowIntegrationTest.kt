package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * `runNow` - 이미 넣은 잡 하나를 지금 임대해서 돌린다(웹훅 즉시 반영의 바탕). 일꾼 스레드는 끄고(`worker-enabled=false`) 직접 부른다.
 * 핵심: 같은 잡을 인라인과 일꾼이 **둘 다** 돌리지 않는다.
 */
@Import(DbTestcontainers::class, JobQueueRunNowIntegrationTest.Fixtures::class)
@SpringBootTest(classes = [JobQueueTestApplication::class], properties = ["skeleton.job-queue.worker-enabled=false", "skeleton.job-queue.backoff.initial=10s"])
class JobQueueRunNowIntegrationTest {
    class MutableTime(var now: Instant = Instant.parse("2026-10-16T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = now
    }

    class GateHandler(override val type: String) : JobHandler {
        val handled = CopyOnWriteArrayList<Long>()
        @Volatile var entered = CountDownLatch(1)
        @Volatile var release: CountDownLatch? = null
        @Volatile var failWith: Exception? = null
        override fun handle(job: Job) {
            handled += job.id
            entered.countDown()
            release?.await(10, TimeUnit.SECONDS)
            failWith?.let { throw it }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
        @Bean fun gate() = GateHandler("gate")
    }

    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var gate: GateHandler

    private fun worker() = JobQueueWorker(repository, listOf(gate), properties, time)
    private fun reset() { gate.handled.clear(); gate.entered = CountDownLatch(1); gate.release = null; gate.failWith = null }

    @Test
    fun `runNow 는 대상 잡만 돌려 DONE 으로 끝내고 다른 잡과 때가 안 된 잡은 건드리지 않는다`() {
        reset()
        val target = queue.enqueue("gate", """{"subjectId":"a"}""")
        val other = queue.enqueue("gate", """{"subjectId":"b"}""")
        val later = queue.enqueue("gate", """{"subjectId":"a"}""", runAt = time.now.plus(Duration.ofHours(1)))

        assertTrue(worker().runNow("gate", """{"subjectId":"a"}"""))

        assertEquals(listOf(target), gate.handled.toList())
        assertEquals(JobStatus.DONE, repository.findById(target)!!.status)
        assertEquals(JobStatus.PENDING, repository.findById(other)!!.status)
        assertEquals(JobStatus.PENDING, repository.findById(later)!!.status)
        assertFalse(worker().runNow("gate", """{"subjectId":"a"}"""), "the due one is done; the later one is not due")
    }

    @Test
    fun `runNow 가 도는 동안 일꾼의 폴링은 같은 잡을 집지 않고 끝나면 다시 돌지 않는다`() {
        reset()
        gate.release = CountDownLatch(1)
        val id = queue.enqueue("gate", """{"subjectId":"slow"}""")
        val inline = thread { worker().runNow("gate", """{"subjectId":"slow"}""") }
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS))

        assertEquals(0, worker().pollOnce(), "the inline run holds the lease")
        assertEquals(JobStatus.RUNNING, repository.findById(id)!!.status)

        gate.release!!.countDown()
        inline.join(5_000)
        assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
        assertEquals(0, worker().pollOnce())
        assertEquals(listOf(id), gate.handled.toList(), "handled exactly once")
    }

    @Test
    fun `runNow 와 폴링이 동시에 같은 잡으로 달려도 한쪽만 돈다`() {
        repeat(10) { round ->
            reset()
            val id = queue.enqueue("gate", """{"subjectId":"race$round"}""")
            val start = CountDownLatch(1)
            val ran = CopyOnWriteArrayList<Boolean>()
            val a = thread { start.await(); ran += worker().runNow("gate", """{"subjectId":"race$round"}""") }
            val b = thread { start.await(); ran += (worker().pollOnce() > 0) }
            start.countDown()
            a.join(10_000); b.join(10_000)
            assertEquals(1, ran.count { it }, "round $round: exactly one side ran it")
            assertEquals(listOf(id), gate.handled.toList(), "round $round")
            assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
        }
    }

    @Test
    fun `runNow 가 실패하면 일반 잡과 같이 백오프 재시도로 남는다`() {
        reset()
        gate.failWith = IllegalStateException("boom")
        val id = queue.enqueue("gate", """{"subjectId":"fail"}""")

        assertTrue(worker().runNow("gate", """{"subjectId":"fail"}"""), "it ran, and failed")

        val job = repository.findById(id)!!
        assertEquals(JobStatus.PENDING, job.status)
        assertEquals(1, job.attempts)
        assertEquals(time.now.plus(Duration.ofSeconds(10)), job.nextRunAt)
    }
}
