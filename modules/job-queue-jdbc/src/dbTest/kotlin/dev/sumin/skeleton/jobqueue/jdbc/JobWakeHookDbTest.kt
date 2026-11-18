package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.JobWake
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** 잡을 넣으면 커밋 뒤에(트랜잭션 밖이면 바로) 깨우기 예약이 나가고, drain 이 끝나면 남은 가장 이른 일감 시각으로 다시 무장한다 */
@Import(DbTestcontainers::class, JobWakeHookDbTest.Fixtures::class)
@SpringBootTest(classes = [JobQueueTestApplication::class], properties = ["skeleton.job-queue.worker-enabled=false"])
class JobWakeHookDbTest {
    class MutableTime(var now: Instant = Instant.parse("2026-11-18T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = now
    }

    class NoopHandler(override val type: String) : JobHandler {
        override fun handle(job: Job) = Unit
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean fun time() = MutableTime()
        @Bean fun wakeHookHandler() = NoopHandler(TYPE)
        @Bean fun recordingWake() = RecordingWake()
    }

    class RecordingWake : JobWake {
        val requests = CopyOnWriteArrayList<Instant>()
        override fun request(at: Instant) { requests += at }
    }

    @Autowired lateinit var queue: JobQueue
    @Autowired lateinit var repository: JdbcJobRepository
    @Autowired lateinit var properties: JobQueueProperties
    @Autowired lateinit var time: MutableTime
    @Autowired lateinit var wake: RecordingWake
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var handler: NoopHandler

    private val tx get() = TransactionTemplate(transactionManager)
    private fun worker() = JobQueueWorker(repository, listOf(handler), properties, time, jobWake = wake)

    @BeforeEach
    fun clean() {
        jdbc.sql("delete from jobs where job_type = :type").param("type", TYPE).update()
        wake.requests.clear()
    }

    @Test
    fun `requests wake after commit at run time`() {
        val runAt = time.now.plus(Duration.ofMinutes(10))
        tx.executeWithoutResult {
            queue.enqueue(TYPE, "{}", runAt = runAt)
            assertTrue(wake.requests.isEmpty(), "커밋 전에는 예약하지 않는다")
        }
        assertEquals(listOf(runAt), wake.requests.toList())
    }

    @Test
    fun `no wake when enqueue transaction rolls back`() {
        tx.executeWithoutResult { status ->
            queue.enqueue(TYPE, "{}", runAt = time.now.plus(Duration.ofMinutes(10)))
            status.setRollbackOnly()
        }
        assertTrue(wake.requests.isEmpty(), "커밋되지 않은 일은 깨우기를 예약하지 않는다: ${wake.requests}")
    }

    @Test
    fun `requests wake immediately outside a transaction`() {
        queue.enqueue(TYPE, "{}")
        assertEquals(listOf(time.now), wake.requests.toList())
    }

    @Test
    fun `drain re-arms at earliest pending run time`() {
        val soon = time.now.plus(Duration.ofHours(1))
        queue.enqueue(TYPE, "{}", runAt = soon.plus(Duration.ofHours(1)))
        queue.enqueue(TYPE, "{}", runAt = soon)
        wake.requests.clear()

        worker().drain(Duration.ofSeconds(5))

        assertEquals(listOf(soon), wake.requests.toList())
    }

    @Test
    fun `no re-arm when queue is empty`() {
        worker().drain(Duration.ofSeconds(5))
        assertTrue(wake.requests.isEmpty())
    }

    private companion object {
        const val TYPE = "wakehook"
    }
}
