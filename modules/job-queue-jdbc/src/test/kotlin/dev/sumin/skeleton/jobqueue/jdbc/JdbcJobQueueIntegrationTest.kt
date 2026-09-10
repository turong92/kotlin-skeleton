package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** 실제 MySQL(Testcontainers) 에서 claim / 재시도 백오프 / DEAD / 스테일 복구 / SKIP LOCKED 를 검증. 워커 스레드는 끄고 pollOnce 로 직접 돌린다. */
@Import(JobQueueTestcontainers::class, JdbcJobQueueIntegrationTest.Fixtures::class)
@SpringBootTest(
    classes = [JobQueueTestApplication::class],
    properties = [
        "skeleton.job-queue.worker-enabled=false",
        "skeleton.job-queue.max-attempts=3",
        "skeleton.job-queue.backoff.initial=10s",
        "skeleton.job-queue.backoff.multiplier=2",
        "skeleton.job-queue.stale-lock-timeout=5m",
    ],
)
class JdbcJobQueueIntegrationTest {
    class MutableTime(var now: Instant = Instant.parse("2026-09-10T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = now
    }

    class RecordingHandler(override val type: String, private val failUntilAttempt: Int = 0, private val permanent: Boolean = false) : JobHandler {
        val handled = CopyOnWriteArrayList<Job>()
        override fun handle(job: Job) {
            handled += job
            if (permanent) throw PermanentJobFailureException("never")
            if (job.attempts <= failUntilAttempt) throw IllegalStateException("boom attempt ${job.attempts}")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
        @Bean fun okHandler() = RecordingHandler("ok")
        @Bean fun flakyHandler() = RecordingHandler("flaky", failUntilAttempt = 2)
        @Bean fun alwaysFailHandler() = RecordingHandler("always-fail", failUntilAttempt = 99)
        @Bean fun permanentHandler() = RecordingHandler("permanent", permanent = true)
    }

    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var handlers: List<JobHandler>

    private fun worker(id: String = "w1") = JobQueueWorker(repository, handlers, properties, time)

    @Test
    fun `성공한 잡은 DONE, 미래 잡은 시각이 될 때까지 안 잡힌다`() {
        val id = queue.enqueue("ok", """{"n":1}""")
        val future = queue.enqueue("ok", """{"n":2}""", runAt = time.now.plus(Duration.ofHours(1)))

        assertEquals(1, worker().pollOnce())
        assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
        assertEquals(JobStatus.PENDING, repository.findById(future)!!.status)

        time.now = time.now.plus(Duration.ofHours(1))
        assertEquals(1, worker().pollOnce())
        assertEquals(JobStatus.DONE, repository.findById(future)!!.status)
    }

    @Test
    fun `실패하면 attempts 가 오르고 백오프 뒤 재시도, 결국 성공`() {
        val id = queue.enqueue("flaky", "{}")

        worker().pollOnce()
        var job = repository.findById(id)!!
        assertEquals(JobStatus.PENDING, job.status)
        assertEquals(1, job.attempts)
        assertEquals(time.now.plus(Duration.ofSeconds(10)), job.nextRunAt)
        assertTrue(job.lastError!!.contains("boom attempt 1"))

        assertEquals(0, worker().pollOnce()) // 아직 백오프 중
        time.now = time.now.plus(Duration.ofSeconds(10))
        worker().pollOnce()
        job = repository.findById(id)!!
        assertEquals(2, job.attempts)
        assertEquals(time.now.plus(Duration.ofSeconds(20)), job.nextRunAt) // initial * multiplier

        time.now = time.now.plus(Duration.ofSeconds(20))
        worker().pollOnce()
        assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
    }

    @Test
    fun `max-attempts 를 넘기면 DEAD, PermanentJobFailureException 은 즉시 DEAD`() {
        val id = queue.enqueue("always-fail", "{}")
        repeat(3) {
            worker().pollOnce()
            time.now = time.now.plus(Duration.ofHours(1))
        }
        val dead = repository.findById(id)!!
        assertEquals(JobStatus.DEAD, dead.status)
        assertEquals(3, dead.attempts)

        val permanent = queue.enqueue("permanent", "{}")
        worker().pollOnce()
        assertEquals(JobStatus.DEAD, repository.findById(permanent)!!.status)
        assertEquals(1, repository.findById(permanent)!!.attempts)
    }

    @Test
    fun `핸들러 없는 타입은 DEAD`() {
        val id = queue.enqueue("unknown-type", "{}")
        worker().pollOnce()
        assertEquals(JobStatus.DEAD, repository.findById(id)!!.status)
    }

    @Test
    fun `두 워커가 동시에 claim 해도 같은 잡을 두 번 집지 않는다 (FOR UPDATE SKIP LOCKED)`() {
        val ids = (1..6).map { queue.enqueue("ok", """{"i":$it}""") }.toSet()
        val a = repository.claim("A", 4, time.now)
        val b = repository.claim("B", 4, time.now)
        assertEquals(4, a.size)
        assertEquals(2, b.size)
        assertEquals(ids, (a + b).map { it.id }.toSet())
        assertTrue((a + b).all { it.status == JobStatus.RUNNING && it.attempts == 1 })
    }

    @Test
    fun `죽은 워커가 남긴 RUNNING 은 stale-lock-timeout 뒤 PENDING 으로 복구된다`() {
        val id = queue.enqueue("ok", "{}")
        val claimed = repository.claim("dead-worker", 1, time.now)
        assertEquals(1, claimed.size)
        assertNotNull(repository.findById(id)!!.lockedAt)

        time.now = time.now.plus(Duration.ofMinutes(6))
        assertEquals(1, worker("w2").pollOnce()) // recoverStale → PENDING → 같은 poll 에서 잡아서 DONE
        assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
    }
}
