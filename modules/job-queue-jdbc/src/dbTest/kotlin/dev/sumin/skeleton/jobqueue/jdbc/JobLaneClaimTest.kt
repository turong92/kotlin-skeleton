package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient

/** 차선 — 후보 일꾼은 자기 차선의 잡만, 기본 일꾼은 차선 없는 잡(과 유예가 지난 차선 잡)만 집는다. PostgreSQL / MySQL 두 벌 */
@Import(DbTestcontainers::class, JobLaneClaimTest.Fixtures::class)
@SpringBootTest(
    classes = [JobQueueTestApplication::class],
    properties = ["skeleton.job-queue.worker-enabled=false", "skeleton.job-queue.lane-grace=1h"],
)
class JobLaneClaimTest {
    class MutableTime(var now: Instant = Instant.parse("2026-11-01T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = now
    }

    class SwitchableLanes : JobLanes {
        @Volatile var enqueue: String? = null
        override fun enqueueLane(): String? = enqueue
        override fun workerLane(): String? = null
    }

    class Recording(override val type: String) : JobHandler {
        val handled = CopyOnWriteArrayList<Job>()
        override fun handle(job: Job) { handled += job }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
        @Bean fun lanes() = SwitchableLanes()
        @Bean fun laneHandler() = Recording("lane-job")
    }

    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var lanes: SwitchableLanes
    @Autowired lateinit var handler: Recording
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dialect: dev.sumin.skeleton.persistence.jdbc.SqlDialect

    @BeforeEach
    fun reset() {
        jdbc.sql("delete from jobs").update()
        time.now = Instant.parse("2026-11-01T00:00:00Z")
        lanes.enqueue = null
        handler.handled.clear()
    }

    private fun cutoff() = time.now.minus(properties.laneGrace)

    private fun insert(lane: String?, runAt: Instant = time.now, payload: String = "{}") =
        repository.insert("lane-job", payload, runAt, 3, time.now, null, lane)

    private fun defaultClaim(limit: Int = 10) = repository.claim("w", limit, time.now, null, null, cutoff())
    private fun laneClaim(lane: String, limit: Int = 10) = repository.claim("w", limit, time.now, null, lane, cutoff())

    @Test
    fun `기본 일꾼은 차선 잡을 건너뛰고 차선 없는 잡은 집는다`() {
        insert("candidate")
        val plain = insert(null)
        assertEquals(listOf(plain), defaultClaim().map { it.id })
    }

    @Test
    fun `차선 일꾼은 자기 차선의 잡만 집는다`() {
        val mine = insert("candidate")
        insert(null)
        insert("alice")
        val claimed = laneClaim("candidate")
        assertEquals(listOf(mine), claimed.map { it.id })
        assertEquals("candidate", claimed.single().lane)
    }

    @Test
    fun `유예가 지난 차선 잡은 기본 일꾼이 집는다`() {
        val stuck = insert("candidate", runAt = time.now.minus(Duration.ofHours(2)))
        val fresh = insert("candidate", runAt = time.now.minus(Duration.ofMinutes(10)))
        assertEquals(listOf(stuck), defaultClaim().map { it.id })
        assertTrue(fresh !in defaultClaim().map { it.id })
    }

    @Test
    fun `defaultWorkerTakesLaneJobAfterGrace - 일꾼 한 판으로`() {
        val stuck = insert("candidate", runAt = time.now.minus(Duration.ofHours(2)))
        val worker = JobQueueWorker(repository, listOf(handler), properties.copy(workerId = "base"), time)
        assertEquals(1, worker.pollOnce())
        assertEquals(listOf(stuck), handler.handled.map { it.id })
    }

    @Test
    fun `defaultWorkerSkipsLaneJobBeforeGrace - 일꾼 한 판으로`() {
        insert("candidate", runAt = time.now.minus(Duration.ofMinutes(5)))
        val worker = JobQueueWorker(repository, listOf(handler), properties.copy(workerId = "base"), time)
        assertEquals(0, worker.pollOnce())
    }

    @Test
    fun `claimOne 도 차선을 지킨다`() {
        insert("candidate", payload = """{"a":1}""")
        assertNull(repository.claimOne("w", "lane-job", """{"a":1}""", time.now, null, cutoff()))
        assertEquals("candidate", repository.claimOne("w", "lane-job", """{"a":1}""", time.now, "candidate", cutoff())?.lane)
    }

    @Test
    fun `넣을 때 JobLanes 의 차선이 줄에 저장된다`() {
        lanes.enqueue = "candidate"
        val withLane = queue.enqueue("lane-job", "{}")
        lanes.enqueue = null
        val without = queue.enqueue("lane-job", "{}")
        assertEquals("candidate", repository.findById(withLane)!!.lane)
        assertNull(repository.findById(without)!!.lane)
    }

    @Test
    fun `차선 일꾼은 정리 일을 건너뛴다`() {
        val stale = insert(null)
        jdbc.sql("update jobs set status='RUNNING', locked_by='dead', locked_at=:at where id=:id")
            .param("at", dialect.instantParam(time.now.minus(Duration.ofHours(1)))).param("id", stale).update()
        val orphan = repository.insert("no-handler", "{}", time.now.minus(Duration.ofDays(3)), 3, time.now)
        val candidateLanes = object : JobLanes {
            override fun enqueueLane(): String? = null
            override fun workerLane(): String? = "candidate"
        }
        val worker = JobQueueWorker(repository, listOf(handler), properties.copy(workerId = "cand"), time, lanes = candidateLanes)
        worker.pollOnce()
        assertEquals(JobStatus.RUNNING, repository.findById(stale)!!.status)
        assertEquals(JobStatus.PENDING, repository.findById(orphan)!!.status)
    }

    @Test
    fun `차선 열이 null 인 옛 줄도 기본 일꾼이 집는다`() {
        val id = insert(null)
        jdbc.sql("update jobs set lane = null where id = :id").param("id", id).update()
        assertEquals(listOf(id), defaultClaim().map { it.id })
    }
}
