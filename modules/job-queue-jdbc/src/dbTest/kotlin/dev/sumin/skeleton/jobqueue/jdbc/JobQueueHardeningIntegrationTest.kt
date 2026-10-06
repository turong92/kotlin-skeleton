package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 임대(lease) 규칙 · DEAD 고리 · 보관 정리 · 로그 문맥 — PostgreSQL / MySQL 두 벌에서 같은 소스로 돈다.
 * 워커 스레드는 끄고 [JobQueueWorker.pollOnce] 와 저장소를 직접 부른다.
 */
@Import(DbTestcontainers::class, JobQueueHardeningIntegrationTest.Fixtures::class)
@SpringBootTest(
    classes = [JobQueueTestApplication::class],
    properties = [
        "skeleton.job-queue.worker-enabled=false",
        "skeleton.job-queue.max-attempts=3",
        "skeleton.job-queue.backoff.initial=10s",
        "skeleton.job-queue.stale-lock-timeout=5m",
        "skeleton.job-queue.propagated-mdc-keys=traceId,tenant",
    ],
)
class JobQueueHardeningIntegrationTest {
    class MutableTime(var now: Instant = Instant.parse("2026-10-01T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = now
    }

    /** 처리 중에 임의 동작을 끼워 넣을 수 있는 핸들러 */
    class ScriptedHandler(override val type: String) : JobHandler {
        val handled = CopyOnWriteArrayList<Job>()
        val seenMdc = CopyOnWriteArrayList<Map<String, String?>>()
        @Volatile var action: (Job) -> Unit = {}
        override fun handle(job: Job) {
            handled += job
            seenMdc += listOf("traceId", "tenant", "other", "jobId", "jobKind").associateWith { MDC.get(it) }
            action(job)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
        @Bean fun scripted() = ScriptedHandler("scripted")
        @Bean fun failing() = ScriptedHandler("failing").also { it.action = { throw IllegalStateException("boom") } }
        @Bean fun permanent() = ScriptedHandler("permanent").also { it.action = { throw PermanentJobFailureException("never") } }
    }

    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var handlers: List<JobHandler>
    @Autowired lateinit var propagator: JobContextPropagator
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var scripted: ScriptedHandler
    @Autowired lateinit var dialect: dev.sumin.skeleton.persistence.jdbc.SqlDialect

    @BeforeEach
    fun reset() {
        jdbc.sql("delete from jobs").update()
        time.now = Instant.parse("2026-10-01T00:00:00Z")
        scripted.handled.clear(); scripted.seenMdc.clear(); scripted.action = {}
        MDC.clear()
    }

    @AfterEach fun cleanMdc() = MDC.clear()

    private fun worker(listeners: List<JobDeadListener> = emptyList(), workerId: String = "w1") =
        JobQueueWorker(repository, handlers, properties.copy(workerId = workerId), time, propagator, listeners)

    // ---- 임대 주인 확인 ----

    @Test
    fun `임대를 잃은 옛 워커의 markDone · markRetry · markDead 는 반영되지 않는다`() {
        val id = queue.enqueue("scripted", "{}")
        val first = repository.claim("A", 1, time.now).single() // attempts 1, A
        time.now = time.now.plus(Duration.ofMinutes(6))
        repository.recoverStale(time.now.minus(Duration.ofMinutes(5)), time.now)
        val second = repository.claim("B", 1, time.now).single() // attempts 2, B
        assertEquals(2, second.attempts)

        assertFalse(repository.markDone(id, "A", first.attempts, time.now))
        assertFalse(repository.markRetry(id, "A", first.attempts, time.now, "late", time.now))
        assertFalse(repository.markDead(id, "A", first.attempts, "late", time.now))

        val job = repository.findById(id)!!
        assertEquals(JobStatus.RUNNING, job.status)
        assertEquals("B", job.lockedBy)
        assertTrue(repository.markDone(id, "B", second.attempts, time.now))
        assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
    }

    @Test
    fun `묶음의 뒤 잡은 차례가 올 때 임대를 새로 잡아 앞 잡이 오래 걸려도 복구되지 않는다`() {
        val a = queue.enqueue("scripted", "{}")
        val b = queue.enqueue("scripted", "{}")
        var statusSeenInSecondJob: JobStatus? = null
        scripted.action = { job ->
            if (job.id == a) {
                time.now = time.now.plus(Duration.ofMinutes(4)) // 앞 잡이 오래 걸린다
            } else {
                time.now = time.now.plus(Duration.ofMinutes(4)) // 뒤 잡도 오래 걸리는 사이, 다른 워커가 복구를 돌린다
                repository.recoverStale(time.now.minus(Duration.ofMinutes(5)), time.now)
                statusSeenInSecondJob = repository.findById(b)!!.status
            }
        }

        worker().pollOnce()

        assertEquals(JobStatus.RUNNING, statusSeenInSecondJob, "차례가 올 때 임대를 새로 잡아야 한다")
        assertEquals(JobStatus.DONE, repository.findById(b)!!.status)
    }

    @Test
    fun `차례가 오기 전에 남에게 넘어간 잡은 돌리지 않는다`() {
        val a = queue.enqueue("scripted", "{}")
        val b = queue.enqueue("scripted", "{}")
        scripted.action = { job ->
            if (job.id == a) { // 앞 잡이 오래 걸리는 사이 다른 워커가 복구하고 둘 다 가져간다
                time.now = time.now.plus(Duration.ofMinutes(6))
                repository.recoverStale(time.now.minus(Duration.ofMinutes(5)), time.now)
                repository.claim("other", 10, time.now)
            }
        }

        worker().pollOnce()

        assertEquals(listOf(a), scripted.handled.map { it.id }, "b 는 이미 남의 것이라 돌리지 않는다")
        assertEquals("other", repository.findById(b)!!.lockedBy)
        assertEquals(JobStatus.RUNNING, repository.findById(b)!!.status)
    }

    // ---- 스테일 복구 → DEAD ----

    @Test
    fun `시도를 다 쓴 채 멈춘 RUNNING 은 PENDING 이 아니라 DEAD 로 간다`() {
        val id = queue.enqueue("scripted", "{}", maxAttempts = 1)
        repository.claim("dead-worker", 1, time.now) // attempts 1 == max
        time.now = time.now.plus(Duration.ofMinutes(6))

        val result = repository.recoverStale(time.now.minus(Duration.ofMinutes(5)), time.now)

        assertEquals(0, result.recovered)
        assertEquals(listOf(id), result.dead.map { it.id })
        val job = repository.findById(id)!!
        assertEquals(JobStatus.DEAD, job.status)
        assertNotNull(job.lastError)
    }

    @Test
    fun `시도가 남은 멈춘 RUNNING 은 여전히 PENDING 으로 복구된다`() {
        val id = queue.enqueue("scripted", "{}", maxAttempts = 3)
        repository.claim("dead-worker", 1, time.now)
        time.now = time.now.plus(Duration.ofMinutes(6))

        val result = repository.recoverStale(time.now.minus(Duration.ofMinutes(5)), time.now)

        assertEquals(1, result.recovered)
        assertTrue(result.dead.isEmpty())
        assertEquals(JobStatus.PENDING, repository.findById(id)!!.status)
    }

    // ---- DEAD 고리 ----

    @Test
    fun `재시도를 다 쓴 잡 · 영구 실패 · 처리기 없음이 DEAD 가 될 때 리스너가 한 번씩 불린다`() {
        val heard = CopyOnWriteArrayList<Pair<Job, String>>()
        val listener = JobDeadListener { job, reason -> heard += job to reason }
        val exhausted = queue.enqueue("failing", "{}", maxAttempts = 1)
        val permanent = queue.enqueue("permanent", "{}")
        val unknown = queue.enqueue("no-such-type", "{}")

        worker(listOf(listener)).pollOnce()

        assertEquals(setOf(exhausted, permanent, unknown), heard.map { it.first.id }.toSet())
        assertEquals(3, heard.size)
        assertTrue(heard.first { it.first.id == exhausted }.second.contains("boom"))
        assertTrue(heard.first { it.first.id == unknown }.second.contains("no-such-type"))
    }

    @Test
    fun `재시도가 남은 실패와 성공에는 리스너가 불리지 않는다`() {
        val heard = CopyOnWriteArrayList<Job>()
        queue.enqueue("failing", "{}", maxAttempts = 5)
        queue.enqueue("scripted", "{}")

        worker(listOf(JobDeadListener { job, _ -> heard += job })).pollOnce()

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `스테일 복구로 DEAD 가 된 잡은 복구를 돌린 워커가 리스너에 알린다`() {
        val id = queue.enqueue("scripted", "{}", maxAttempts = 1)
        repository.claim("dead-worker", 1, time.now)
        time.now = time.now.plus(Duration.ofMinutes(6))
        val heard = CopyOnWriteArrayList<Long>()

        worker(listOf(JobDeadListener { job, _ -> heard += job.id })).pollOnce()

        assertEquals(listOf(id), heard)
    }

    @Test
    fun `리스너가 던져도 워커는 계속 돌고 나머지 리스너도 불린다`() {
        val heard = CopyOnWriteArrayList<Long>()
        val id = queue.enqueue("permanent", "{}")
        val other = queue.enqueue("scripted", "{}")

        worker(listOf(JobDeadListener { _, _ -> error("listener down") }, JobDeadListener { job, _ -> heard += job.id })).pollOnce()

        assertEquals(listOf(id), heard)
        assertEquals(JobStatus.DONE, repository.findById(other)!!.status)
    }

    // ---- 보관 정리 ----

    @Test
    fun `보관 기간이 지난 DONE · DEAD 만 지우고 PENDING · RUNNING 과 최근 줄은 남긴다`() {
        fun insert(status: String, age: Duration): Long {
            val id = queue.enqueue("scripted", "{}")
            jdbc.sql("update jobs set status = :s, updated_at = :t where id = :id")
                .param("s", status).param("id", id)
                .param("t", dialect.instantParam(time.now.minus(age))).update()
            return id
        }
        val oldDone = insert("DONE", Duration.ofDays(15))
        val freshDone = insert("DONE", Duration.ofDays(1))
        val oldDead = insert("DEAD", Duration.ofDays(91))
        val midDead = insert("DEAD", Duration.ofDays(30))
        val oldPending = insert("PENDING", Duration.ofDays(400))
        val oldRunning = insert("RUNNING", Duration.ofDays(400))
        val retention = JobRetention(repository, JobQueueProperties.Retention(enabled = true, done = Duration.ofDays(14), dead = Duration.ofDays(90), batchSize = 1), time)

        val result = retention.run(time.now)

        assertEquals(JobRetentionResult(done = 1, dead = 1), result)
        assertNull(repository.findById(oldDone)); assertNull(repository.findById(oldDead))
        listOf(freshDone, midDead, oldPending, oldRunning).forEach { assertNotNull(repository.findById(it)) }
    }

    // ---- 로그 문맥 ----

    @Test
    fun `넣는 쪽 MDC 의 설정한 키만 저장되고 워커가 돌리는 동안 복원되며 끝나면 깨끗하다`() {
        MDC.put("traceId", "0123456789abcdef0123456789abcdef")
        MDC.put("tenant", "acme corp&co")
        MDC.put("other", "must-not-travel")
        val id = queue.enqueue("scripted", "{}")
        MDC.clear() // 워커 스레드는 요청 스레드가 아니다

        assertNotNull(repository.findById(id)!!.logContext)
        worker().pollOnce()

        val seen = scripted.seenMdc.single()
        assertEquals("0123456789abcdef0123456789abcdef", seen["traceId"])
        assertEquals("acme corp&co", seen["tenant"])
        assertNull(seen["other"])
        assertEquals(id.toString(), seen["jobId"])
        assertEquals("scripted", seen["jobKind"])
        listOf("traceId", "tenant", "jobId", "jobKind").forEach { assertNull(MDC.get(it), "$it leaked") }
    }

    @Test
    fun `문맥 없이 넣은 잡은 log_context 가 null 이고 그대로 돈다`() {
        val id = queue.enqueue("scripted", "{}")
        assertNull(repository.findById(id)!!.logContext)
        worker().pollOnce()
        assertEquals(JobStatus.DONE, repository.findById(id)!!.status)
    }
}
